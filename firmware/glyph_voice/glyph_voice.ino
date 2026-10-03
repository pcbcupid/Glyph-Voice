#include <Arduino.h>
#include <WiFi.h>
#include <WebSocketsServer.h>
#include <atomic>
#include "I2SMicrophone.h"
#include "config.h"
#include "StopCommand.h"
#include "WifiSetup.h"
#include "BootSetupWindow.h"
#include "SerialCommands.h"
#include "StatusLed.h"

WifiSetup wifiSetup;

#if !CONFIG_IDF_TARGET_ESP32C6
#error "Select Pcbcupid GLYPH C6 (ESP32-C6), not ESP32/C3/S3."
#endif
#if !ARDUINO_USB_CDC_ON_BOOT
#error "Enable USB CDC On Boot. UART0 conflicts with microphone GPIO16/17."
#endif

enum class PacketKind : uint8_t { Start, Audio, End };
enum class StopOrigin : uint8_t { None, Boot, App };
enum class CaptureFault : uint32_t { None, QueueFull, DmaOverrun, ReadError };
struct Packet {
  uint32_t epoch;
  uint32_t recording;
  PacketKind kind;
  StopOrigin stoppedBy;
  uint16_t bytes;
  // WebSockets can prepend its header here: no malloc/memcpy for every send.
  uint8_t frame[WEBSOCKETS_MAX_HEADER_SIZE + Config::FRAMES_PER_PACKET * 2];
};
// Keep the enlarged queue bounded; leave room for I2S, Wi-Fi/TCP and task stacks.
static_assert(sizeof(Packet) * Config::QUEUE_PACKETS <= 170 * 1024, "Audio queue RAM budget exceeded");

I2SMicrophone microphone;
WebSocketsServer socketServer(Config::PORT, "", "");
QueueHandle_t audioQueue = nullptr;
// Zero means disconnected. Epochs prevent queued audio leaking across clients.
std::atomic<uint32_t> connectedEpoch{0};
std::atomic<uint32_t> abortEpoch{0};
std::atomic<uint32_t> requestedStop{0};
std::atomic<bool> microphoneFailed{false};
std::atomic<CaptureFault> captureFault{CaptureFault::None};
std::atomic<uint32_t> readError{ESP_OK};
std::atomic<uint32_t> queueHighWater{0};
std::atomic<uint32_t> maxCaptureGapMs{0};
std::atomic<uint32_t> recordingDmaBaseline{0};
uint32_t epochCounter = 0;
int phone = -1;
uint32_t sendingRecording = 0;
uint64_t sentBytes = 0; // Session totals must not wrap during long recordings.
bool initialized = false;
bool wifiConnected = false;
bool microphoneFailureReported = false;
uint32_t lastWifiAttempt = 0;
uint32_t maxSendMs = 0;
uint32_t lastStreamReport = 0;
uint32_t slowSends = 0;
uint32_t lastSlowSendReport = 0;
constexpr char FIRMWARE_LABEL[] = "GLYPH VOICE transport-r11: personal hotspot + GPIO14 Wi-Fi LED; no Bluetooth; 40ms audio / 128-slot queue";
SerialCommands serialCommands;
void updateStatusLed() {
  const bool connected = WiFi.status() == WL_CONNECTED && WiFi.localIP() != IPAddress(0, 0, 0, 0);
  const bool on = statusLedOn(millis(), connected);
  digitalWrite(Config::STATUS_LED, on == Config::STATUS_LED_ACTIVE_HIGH ? HIGH : LOW);
}

void printSerialHelp() {
  Serial.println("[console] USB Serial Monitor: 115200 baud. Send a command with Enter / Newline (CR, LF or CRLF)");
  Serial.println("[console] HELP       - show these instructions");
  Serial.println("[console] STATUS     - show board ID, Wi-Fi/setup state, recording address and audio state");
  Serial.println("[console] WIFI SETUP - choose a board hotspot name/password, then open setup (disconnect Android/web first)");
  Serial.println("[console] Or reset with BOOT released: tap BOOT during 3...2...1 for setup; do nothing to use saved Wi-Fi");
  Serial.println("[console] BOOT held across reset enters the FLASHING bootloader, not Wi-Fi setup. Router passwords go in the setup web page only");
}

void printSerialStatus() {
  Serial.printf("[firmware] %s\n", FIRMWARE_LABEL);
  wifiSetup.printStatus();
  Serial.printf("[audio] hardware=%s | app=%s | stream=%s | sent=%llu bytes | heap=%u minHeap=%u\n",
                initialized && !microphoneFailed.load() ? "ready" : "not ready; check fatal logs",
                phone >= 0 ? "connected" : "not connected",
                sendingRecording ? "recording" : "idle",
                static_cast<unsigned long long>(sentBytes), ESP.getFreeHeap(), ESP.getMinFreeHeap());
}

void pollSerialConsole() {
  static bool wasConnected = false;
  const bool connected = static_cast<bool>(Serial);
  if (!connected) {
    wasConnected = false;
    serialCommands.clear();
    wifiSetup.serialDisconnected();
    return;
  }
  if (!wasConnected) {
    wasConnected = true;
    // Browser monitors often open AFTER the boot countdown. No reset or waiting
    // for USB is needed to recover the setup URL / current recording address.
    Serial.println("[console] USB monitor attached. No need to reset: send HELP, STATUS or WIFI SETUP + Enter");
    if (!sendingRecording) printSerialStatus();
  }
  // Never use readStringUntil/delay/while(!Serial): live capture must keep running.
  for (unsigned i = 0; i < 32 && Serial.available(); ++i) {
    const char value = static_cast<char>(Serial.read());
    if (wifiSetup.needsSerial()) { wifiSetup.serialByte(value); continue; }
    // A setup-password CRLF may leave its LF after the wizard finishes. The
    // normal command parser treats an empty newline as a harmless no-op.
    const auto command = serialCommands.feed(value);
    if (command == SerialCommands::None) continue;
    switch (command) {
      case SerialCommands::Help: printSerialHelp(); break;
      case SerialCommands::Status: printSerialStatus(); break;
      case SerialCommands::WifiSetup:
        if (phone >= 0 || connectedEpoch.load() != 0 || sendingRecording) {
          Serial.println("[setup] Not changing Wi-Fi: an app is connected. Stop recording, Disconnect Glyph in Android/web, then send WIFI SETUP again");
        } else if (wifiSetup.busy()) {
          wifiSetup.printStatus();
        } else {
          Serial.println("[setup] USB requested configuration. Saved Wi-Fi is kept until new settings connect successfully");
          socketServer.close();
          wifiConnected = false;
          wifiSetup.openFromSerial(value);
        }
        break;
      default: Serial.println("[console] Unknown/too-long command. Send HELP + Enter. Input is never echoed; enter Wi-Fi passwords in the setup page only"); break;
    }
    break; // One response per loop, with audio/socket work in between.
  }
}

void abortCapture(uint32_t epoch, CaptureFault reason) {
  // Publish the reason before the epoch; the network task is the sole log writer.
  captureFault.store(reason);
  abortEpoch.store(epoch);
}

bool enqueuePacket(const Packet& packet) {
  if (xQueueSend(audioQueue, &packet, 0) == pdTRUE) {
    const uint32_t depth = uxQueueMessagesWaiting(audioQueue);
    if (depth > queueHighWater.load()) queueHighWater.store(depth);
    return true;
  }
  abortCapture(packet.epoch, CaptureFault::QueueFull);
  return false;
}

bool finishRecording(Packet& packet, StopOrigin origin) {
  packet.stoppedBy = origin;
  // Flush a short final block BEFORE end. Never lose the final 10 ms of speech.
  if (packet.bytes) {
    packet.kind = PacketKind::Audio;
    if (!enqueuePacket(packet)) return false;
  }
  packet.kind = PacketKind::End;
  packet.bytes = 0;
  return enqueuePacket(packet);
}

// Sole owner of I2S reads and button state. Wi-Fi writes must never delay capture.
void captureTask(void*) {
  Packet packet = {};
  int16_t pcm[Config::PCM_FRAMES_PER_READ];
  bool rawPressed = false;
  bool pressed = false;
  bool armed = false;
  bool recording = false;
  uint32_t changedAt = millis();
  uint32_t settledAt = millis();
  uint32_t epoch = 0;
  uint32_t sequence = 0;
  uint32_t overrunCount = microphone.overruns();
  uint32_t previousReadAt = millis();

  for (;;) {
    const bool raw = digitalRead(Config::RECORD_BUTTON) == LOW;
    if (raw != rawPressed) {
      rawPressed = raw;
      changedAt = millis();
    }
    const bool wasPressed = pressed;
    if (millis() - changedAt >= Config::DEBOUNCE_MS) pressed = rawPressed;
    const bool clicked = pressed && !wasPressed;

    const uint32_t currentEpoch = connectedEpoch.load();
    if (epoch != currentEpoch) {
      epoch = currentEpoch;
      recording = false;
      packet.bytes = 0;
      armed = false;
      // Even when the button changes during a reconnect, require a stable release.
      changedAt = millis();
    }
    const bool healthy = epoch != 0 && abortEpoch.load() != epoch;
    if (!healthy) {
      recording = false;
      armed = false;
    } else if (!pressed && !rawPressed && millis() - changedAt >= Config::DEBOUNCE_MS) {
      armed = true;
    }

    // Check before finalizing too: an overrun between reads must not be hidden
    // by a stop click at the beginning of this iteration.
    if (recording && microphone.overruns() != overrunCount) {
      abortCapture(epoch, CaptureFault::DmaOverrun);
      recording = false;
      armed = false;
    }
    const bool remoteStopped = healthy && recording && requestedStop.load() == packet.recording;
    if (remoteStopped) {
      finishRecording(packet, StopOrigin::App);
      recording = false;
      armed = false;
    }
    // A simultaneous phone stop and BOOT click must not restart capture.
    if (!remoteStopped && healthy && armed && clicked && millis() - settledAt >= Config::MIC_SETTLE_MS) {
      armed = false;
      if (recording) {
        finishRecording(packet, StopOrigin::Boot);
        recording = false;
      } else {
        packet.epoch = epoch;
        if (++sequence == 0) ++sequence;
        packet.recording = sequence;
        packet.kind = PacketKind::Start;
        packet.stoppedBy = StopOrigin::None;
        packet.bytes = 0;
        queueHighWater.store(0);
        maxCaptureGapMs.store(0);
        previousReadAt = millis();
        overrunCount = microphone.overruns();
        recordingDmaBaseline.store(overrunCount);
        recording = enqueuePacket(packet);
      }
    }

    size_t frames = 0;
    const esp_err_t err = microphone.read(pcm, frames);
    const uint32_t readAt = millis();
    const uint32_t gap = readAt - previousReadAt;
    previousReadAt = readAt;
    if (recording && gap > maxCaptureGapMs.load()) maxCaptureGapMs.store(gap);
    if (err != ESP_OK) {
      readError.store(static_cast<uint32_t>(err));
      if (epoch) abortCapture(epoch, CaptureFault::ReadError);
      microphoneFailed.store(true);
      // Never do USB writes from the time-critical capture task.
      vTaskDelete(nullptr);
    }
    if (!recording) continue;  // Idle samples are overwritten, never queued/sent.
    if (connectedEpoch.load() != epoch || abortEpoch.load() == epoch) {
      recording = false;
      armed = false;
      continue;
    }
    if (microphone.overruns() != overrunCount) {
      abortCapture(epoch, CaptureFault::DmaOverrun);
      recording = false;
      armed = false;
      continue;
    }
    packet.kind = PacketKind::Audio;
    for (size_t i = 0; i < frames; ++i) {
      const size_t offset = WEBSOCKETS_MAX_HEADER_SIZE + packet.bytes;
      const uint16_t sample = static_cast<uint16_t>(pcm[i]);
      packet.frame[offset] = sample & 0xff;
      packet.frame[offset + 1] = sample >> 8;
      packet.bytes += 2;
      // Packet boundaries need not coincide with a short I2S read boundary.
      if (packet.bytes == Config::FRAMES_PER_PACKET * 2) {
        if (!enqueuePacket(packet)) {
          recording = false;
          armed = false;
          break;
        }
        packet.bytes = 0;
      }
    }
  }
}

void dropPhone(const char* reason) {
  const int previousPhone = phone;
  connectedEpoch.store(0);
  requestedStop.store(0);
  phone = -1;
  sendingRecording = 0;
  sentBytes = 0;
  Serial.printf("[network] %s\n", reason);
  if (previousPhone >= 0) socketServer.disconnect(previousPhone);
}

void socketEvent(uint8_t client, WStype_t type, uint8_t* payload, size_t length) {
  if (type == WStype_CONNECTED) {
    if (phone >= 0 || wifiSetup.busy() || microphoneFailed.load() ||
        length != strlen(Config::AUDIO_PATH) || memcmp(payload, Config::AUDIO_PATH, length) != 0) {
      socketServer.disconnect(client);
      return;
    }
    phone = client;
    if (++epochCounter == 0) ++epochCounter;
    connectedEpoch.store(epochCounter);
    Serial.println("[network] Phone connected; click BOOT to start, click again to stop");
  } else if (client == phone && type == WStype_DISCONNECTED) {
    dropPhone("Phone disconnected; incomplete audio discarded");
  } else if (client == phone && type == WStype_TEXT) {
    const uint32_t id = parseStopCommand(payload, length);
    if (!id) { dropPhone("Invalid stop command"); return; }
    // Late/duplicate requests are harmless. Never stop a newer recording.
    if (id == sendingRecording) requestedStop.store(id);
  } else if (client == phone && (type == WStype_BIN ||
             type == WStype_FRAGMENT_TEXT_START || type == WStype_FRAGMENT_BIN_START ||
             type == WStype_FRAGMENT || type == WStype_FRAGMENT_FIN || type == WStype_ERROR)) {
    dropPhone("Unexpected client data/error");
  }
  // The WebSockets library handles standard ping/pong automatically.
}

bool sendPacket(Packet& packet) {
  char message[180];
  if (packet.kind == PacketKind::Start) {
    if (sendingRecording != 0) return false;
    sendingRecording = packet.recording;
    sentBytes = 0;
    maxSendMs = 0;
    slowSends = 0;
    lastSlowSendReport = 0;
    lastStreamReport = millis();
    snprintf(message, sizeof(message),
             "{\"type\":\"start\",\"version\":1,\"id\":\"r%lu\",\"sampleRate\":%lu,\"channels\":1,\"encoding\":\"pcm_s16le\",\"control\":\"stop-v1\"}",
             static_cast<unsigned long>(packet.recording), static_cast<unsigned long>(Config::SAMPLE_RATE));
    Serial.println("[audio] Live audio streaming; no duration limit. BOOT or app STOP ends recording.");
    return socketServer.sendTXT(phone, message);
  }
  if (sendingRecording != packet.recording) return false;
  if (packet.kind == PacketKind::Audio) {
    if (!socketServer.sendBIN(phone, packet.frame, packet.bytes, true)) return false;
    sentBytes += packet.bytes;
    return true;
  }
  snprintf(message, sizeof(message), "{\"type\":\"end\",\"id\":\"r%lu\",\"bytes\":%llu}",
           static_cast<unsigned long>(packet.recording), static_cast<unsigned long long>(sentBytes));
  const bool sent = socketServer.sendTXT(phone, message);
  Serial.printf("[audio] Stop requested by %s\n", packet.stoppedBy == StopOrigin::Boot ? "BOOT button" : "app STOP command");
  Serial.printf("[audio] Stream ended: %llu bytes, %llu ms; phone finalizing last words\n",
                static_cast<unsigned long long>(sentBytes),
                static_cast<unsigned long long>(sentBytes * 1000ULL / (Config::SAMPLE_RATE * 2)));
  sendingRecording = 0;
  Serial.printf("[stats] queuePeak=%lu/%u maxSend=%lu ms maxCaptureGap=%lu ms slowSends=%lu heap=%u minHeap=%u\n",
                static_cast<unsigned long>(queueHighWater.load()), Config::QUEUE_PACKETS,
                static_cast<unsigned long>(maxSendMs), static_cast<unsigned long>(maxCaptureGapMs.load()),
                static_cast<unsigned long>(slowSends),
                ESP.getFreeHeap(), ESP.getMinFreeHeap());
  return sent;
}

bool chooseWifiSetupAtBoot() {
  const uint32_t began = millis();
  BootSetupWindow window(began, digitalRead(Config::RECORD_BUTTON) == LOW);
  unsigned lastSeconds = 0;
  while (true) {
    const uint32_t now = millis();
    updateStatusLed();
    const auto choice = window.update(now, digitalRead(Config::RECORD_BUTTON) == LOW);
    if (choice == BootSetupWindow::ConfigureWifi) {
      Serial.println("[boot] BOOT selected Wi-Fi setup. Release the button; continue in USB Serial Monitor...");
      return true;
    }
    if (choice == BootSetupWindow::SavedWifi) {
      Serial.println("[boot] Startup window complete; using latest saved Wi-Fi (setup if none saved)");
      return false;
    }
    const unsigned seconds = (BootSetupWindow::WINDOW_MS - (now - began) + 999) / 1000;
    if (seconds != lastSeconds) {
      lastSeconds = seconds;
      Serial.printf("[boot] %u... Tap BOOT now for Wi-Fi setup, or wait for saved Wi-Fi\n", seconds);
    }
    delay(5);
  }
}

void setup() {
  Serial.begin(115200);  // Native USB only. Never start Serial0 on microphone pins.
  Serial.setTxBufferSize(4096); // Room for setup instructions; never wait for a USB reader.
  // CDC's default write timeout can stall the sender longer than the audio queue.
  // Prefer dropping diagnostic output when USB is congested to blocking speech.
  Serial.setTxTimeoutMs(0);
  Serial.printf("[firmware] %s\n", FIRMWARE_LABEL);
  Serial.println("[boot] Reset with BOOT released. Tap BOOT during the next THREE seconds for a new Wi-Fi configuration hotspot");
  Serial.println("[boot] Do nothing to join saved Wi-Fi. No saved settings? Setup opens automatically. Send HELP later for USB/browser instructions");
  pinMode(Config::RECORD_BUTTON, INPUT_PULLUP);
  pinMode(Config::STATUS_LED, OUTPUT);
  updateStatusLed();
  const bool configureWifi = chooseWifiSetupAtBoot();
  // Setup remains reachable even if audio hardware initialization fails below.
  wifiSetup.begin(configureWifi);
  lastWifiAttempt = millis();
  audioQueue = xQueueCreate(Config::QUEUE_PACKETS, sizeof(Packet));
  if (!audioQueue) {
    Serial.println("[fatal] Could not allocate audio queue; reset board");
    return;
  }
  const esp_err_t err = microphone.begin();
  if (err != ESP_OK) {
    Serial.printf("[fatal] I2S initialization: %s\n", esp_err_to_name(err));
    return;
  }
  if (xTaskCreate(captureTask, "mic-capture", 6144, nullptr, Config::CAPTURE_TASK_PRIORITY, nullptr) != pdPASS) {
    Serial.println("[fatal] Could not start capture task; reset board");
    return;
  }
  socketServer.onEvent(socketEvent);
  initialized = true;
  Serial.printf("[audio] %lu Hz, PCM16 mono; BOOT GPIO%d click start / click stop\n",
                static_cast<unsigned long>(Config::SAMPLE_RATE), Config::RECORD_BUTTON);
}

void loop() {
  updateStatusLed();
  pollSerialConsole();
  wifiSetup.poll(phone < 0);
  if (wifiSetup.configuring()) { delay(2); return; }
  if (!initialized) {
    delay(100);
    return;
  }
  if (microphoneFailed.load() && !microphoneFailureReported) {
    microphoneFailureReported = true;
    // Report hardware failures even when they happen before a phone connects.
    Serial.printf("[fatal] I2S read failed: %s; reset the Glyph\n",
                  esp_err_to_name(static_cast<esp_err_t>(readError.load())));
  }
  if (WiFi.status() != WL_CONNECTED || WiFi.localIP() == IPAddress(0, 0, 0, 0)) {
    if (wifiConnected) {
      wifiConnected = false;
      dropPhone("Wi-Fi lost; recording stopped. Reconnecting to saved Wi-Fi/router/hotspot");
      socketServer.close();
    }
    if (!wifiSetup.busy() && millis() - lastWifiAttempt >= 15000) {
      lastWifiAttempt = millis();
      WiFi.reconnect();
      Serial.printf("[network] Retrying saved Wi-Fi: %s. Check router/hotspot power, 2.4 GHz and range\n", WifiSetup::connectionState());
      Serial.println("[network] Changed network/password? Send WIFI SETUP + Enter, or reset and TAP BOOT during the 3-second countdown. Saved settings have NOT been erased");
    }
    delay(10);
    return;
  }
  if (!wifiConnected) {
    wifiConnected = true;
    socketServer.begin();
    Serial.printf("[ready] YOUR BOARD: GLYPH-%s | ID=%s\n",
                  wifiSetup.boardId.substring(6).c_str(), wifiSetup.boardId.c_str());
    Serial.printf("[ready] ANDROID / WEB: Connect Glyph -> enter %s:%u (not the Wi-Fi setup address)\n",
                  WiFi.localIP().toString().c_str(), Config::PORT);
    Serial.println("[ready] Wait for the app/model to be ready, then click BOOT to start/stop. One audio client per board.");
    Serial.printf("[ready] Wi-Fi channel=%d RSSI=%d dBm. Keep app/computer and Glyph on the same network; guest/client isolation blocks connections\n", WiFi.channel(), WiFi.RSSI());
    Serial.println("[ready] Setup hotspot is OFF. Need the address again? Send STATUS + Enter. To change networks, disconnect app then send WIFI SETUP");
    Serial.printf("[memory] queue=%u bytes DMA=%u bytes heap=%u minHeap=%u\n",
                  static_cast<unsigned>(sizeof(Packet) * Config::QUEUE_PACKETS),
                  Config::DMA_DESCRIPTORS * Config::CAPTURE_FRAMES * 8,
                  ESP.getFreeHeap(), ESP.getMinFreeHeap());
    Serial.printf("[memory] Audio jitter capacity=%u ms (not a fixed delay). Longer stalls still require recovery; no audio is silently dropped\n",
                  (Config::QUEUE_PACKETS - 2) * Config::FRAMES_PER_PACKET * 1000 / Config::SAMPLE_RATE);
  }
  socketServer.loop();
  Packet packet;
  // A bounded batch prevents a busy audio queue starving socket housekeeping.
  for (unsigned i = 0; i < Config::SEND_BATCH_PACKETS; ++i) {
    const uint32_t epoch = connectedEpoch.load();
    if (epoch && (abortEpoch.load() == epoch || microphoneFailed.load())) {
      Serial.printf("[fault] queue=%u/%u peak=%lu maxSend=%lu ms maxCaptureGap=%lu ms dmaOverruns=%lu sessionDmaOverruns=%lu RSSI=%d heap=%u minHeap=%u\n",
                    static_cast<unsigned>(uxQueueMessagesWaiting(audioQueue)), Config::QUEUE_PACKETS,
                    static_cast<unsigned long>(queueHighWater.load()), static_cast<unsigned long>(maxSendMs),
                    static_cast<unsigned long>(maxCaptureGapMs.load()), static_cast<unsigned long>(microphone.overruns()),
                    static_cast<unsigned long>(microphone.overruns() - recordingDmaBaseline.load()),
                    WiFi.RSSI(), ESP.getFreeHeap(), ESP.getMinFreeHeap());
      switch (captureFault.load()) {
        case CaptureFault::QueueFull:
          dropPhone("Audio send queue full: sustained network backpressure; click BOOT after reconnect"); break;
        case CaptureFault::DmaOverrun:
          dropPhone("I2S DMA overrun: capture was delayed; click BOOT after reconnect"); break;
        default:
          Serial.printf("[audio] I2S read failed: %s\n", esp_err_to_name(static_cast<esp_err_t>(readError.load())));
          dropPhone("I2S driver failure; reset the Glyph"); break;
      }
    }
    if (xQueueReceive(audioQueue, &packet, 0) != pdTRUE) break;
    if (phone < 0 || packet.epoch != connectedEpoch.load()) continue;
    const uint32_t sendStarted = millis();
    const bool sent = sendPacket(packet);
    const uint32_t sendMs = millis() - sendStarted;
    if (sendMs > maxSendMs) maxSendMs = sendMs;
    if (sent && sendMs >= 200) {
      ++slowSends;
      if (slowSends == 1 || millis() - lastSlowSendReport >= 5000) {
        lastSlowSendReport = millis();
        const unsigned queued = uxQueueMessagesWaiting(audioQueue);
        Serial.printf("[network] Slow send returned after %lu ms; queue=%u/%u (~%u ms) slowSends=%lu RSSI=%d heap=%u. Check receiver/router load; strong RSSI alone does not rule out stalls\n",
                      static_cast<unsigned long>(sendMs), queued, Config::QUEUE_PACKETS,
                      queued * Config::FRAMES_PER_PACKET * 1000 / Config::SAMPLE_RATE,
                      static_cast<unsigned long>(slowSends), WiFi.RSSI(), ESP.getFreeHeap());
      }
    }
    if (!sent) dropPhone("Audio socket write failed; incomplete recording discarded");
  }
  if (sendingRecording && millis() - lastStreamReport >= 30000) {
    lastStreamReport = millis();
    Serial.printf("[stream] still sending r%lu audio=%llu s bytes=%llu queue=%u/%u maxSend=%lu ms RSSI=%d heap=%u\n",
                  static_cast<unsigned long>(sendingRecording),
                  static_cast<unsigned long long>(sentBytes / (Config::SAMPLE_RATE * 2)),
                  static_cast<unsigned long long>(sentBytes),
                  static_cast<unsigned>(uxQueueMessagesWaiting(audioQueue)), Config::QUEUE_PACKETS,
                  static_cast<unsigned long>(maxSendMs), WiFi.RSSI(), ESP.getFreeHeap());
  }
  delay(1);
}
