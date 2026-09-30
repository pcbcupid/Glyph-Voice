#include <Arduino.h>
#include <WiFi.h>
#include <WebSocketsServer.h>
#include <atomic>
#include "I2SMicrophone.h"
#include "config.h"
#include "StopCommand.h"
#include <WiFiUdp.h>
#include "WifiSetup.h"

WifiSetup wifiSetup;
WiFiUDP discovery;
uint32_t lastAnnouncement = 0;

#if !CONFIG_IDF_TARGET_ESP32C6
#error "Select Pcbcupid GLYPH C6 (ESP32-C6), not ESP32/C3/S3."
#endif
#if !ARDUINO_USB_CDC_ON_BOOT
#error "Enable USB CDC On Boot. UART0 conflicts with microphone GPIO16/17."
#endif

enum class PacketKind : uint8_t { Start, Audio, End };
enum class CaptureFault : uint32_t { None, QueueFull, DmaOverrun, ReadError };
struct Packet {
  uint32_t epoch;
  uint32_t recording;
  PacketKind kind;
  uint16_t bytes;
  // WebSockets can prepend its header here: no malloc/memcpy for every send.
  uint8_t frame[WEBSOCKETS_MAX_HEADER_SIZE + Config::FRAMES_PER_PACKET * 2];
};

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
uint32_t epochCounter = 0;
int phone = -1;
uint32_t sendingRecording = 0;
uint64_t sentBytes = 0; // Session totals must not wrap during long recordings.
bool initialized = false;
bool wifiConnected = false;
bool microphoneFailureReported = false;
bool closeBluetooth = false;
uint32_t lastWifiAttempt = 0;
uint32_t maxSendMs = 0;

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

bool finishRecording(Packet& packet) {
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
      finishRecording(packet);
      recording = false;
      armed = false;
    }
    // A simultaneous phone stop and BOOT click must not restart capture.
    if (!remoteStopped && healthy && armed && clicked && millis() - settledAt >= Config::MIC_SETTLE_MS) {
      armed = false;
      if (recording) {
        finishRecording(packet);
        recording = false;
      } else {
        packet.epoch = epoch;
        if (++sequence == 0) ++sequence;
        packet.recording = sequence;
        packet.kind = PacketKind::Start;
        packet.bytes = 0;
        queueHighWater.store(0);
        maxCaptureGapMs.store(0);
        previousReadAt = millis();
        overrunCount = microphone.overruns();
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
    closeBluetooth = true;
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
    snprintf(message, sizeof(message),
             "{\"type\":\"start\",\"version\":1,\"id\":\"r%lu\",\"sampleRate\":%lu,\"channels\":1,\"encoding\":\"pcm_s16le\",\"control\":\"stop-v1\"}",
             static_cast<unsigned long>(packet.recording), static_cast<unsigned long>(Config::SAMPLE_RATE));
    Serial.println("[audio] Live audio streaming; phone displays partial words");
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
  Serial.printf("[audio] Stream ended: %llu bytes, %llu ms; phone finalizing last words\n",
                static_cast<unsigned long long>(sentBytes),
                static_cast<unsigned long long>(sentBytes * 1000ULL / (Config::SAMPLE_RATE * 2)));
  sendingRecording = 0;
  Serial.printf("[stats] queuePeak=%lu/%u maxSend=%lu ms maxCaptureGap=%lu ms heap=%u minHeap=%u\n",
                static_cast<unsigned long>(queueHighWater.load()), Config::QUEUE_PACKETS,
                static_cast<unsigned long>(maxSendMs), static_cast<unsigned long>(maxCaptureGapMs.load()),
                ESP.getFreeHeap(), ESP.getMinFreeHeap());
  return sent;
}

void setup() {
  Serial.begin(115200);  // Native USB only. Never start Serial0 on microphone pins.
  Serial.setTxBufferSize(2048); // Room for status bursts; never wait for a USB reader.
  // CDC's default write timeout can stall the sender longer than the audio queue.
  // Prefer dropping diagnostic output when USB is congested to blocking speech.
  Serial.setTxTimeoutMs(0);
  Serial.println("[firmware] GLYPH VOICE 0.12.0 transport-r7: authenticated BLE Wi-Fi setup and discovery");
  pinMode(Config::RECORD_BUTTON, INPUT_PULLUP);
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
  wifiSetup.begin();
  lastWifiAttempt = millis();
  socketServer.onEvent(socketEvent);
  initialized = true;
  Serial.printf("[audio] %lu Hz, PCM16 mono; BOOT GPIO%d click start / click stop\n",
                static_cast<unsigned long>(Config::SAMPLE_RATE), Config::RECORD_BUTTON);
}

void loop() {
  if (!initialized) {
    delay(100);
    return;
  }
  wifiSetup.poll(phone < 0);
  if (wifiSetup.active) { delay(2); return; }
  if (microphoneFailed.load() && !microphoneFailureReported) {
    microphoneFailureReported = true;
    // Report hardware failures even when they happen before a phone connects.
    Serial.printf("[fatal] I2S read failed: %s; reset the Glyph\n",
                  esp_err_to_name(static_cast<esp_err_t>(readError.load())));
  }
  if (WiFi.status() != WL_CONNECTED || WiFi.localIP() == IPAddress(0, 0, 0, 0)) {
    if (wifiConnected) {
      wifiConnected = false;
      dropPhone("Wi-Fi lost; recording stopped. Reconnecting to phone hotspot");
      socketServer.close();
      discovery.stop();
    }
    if (!wifiSetup.busy() && millis() - lastWifiAttempt >= 15000) {
      lastWifiAttempt = millis();
      WiFi.reconnect();
      Serial.println("[network] Retrying hotspot connection");
    }
    delay(10);
    return;
  }
  if (!wifiConnected) {
    wifiConnected = true;
    socketServer.begin();
    discovery.begin(40124);
    Serial.printf("[ready] Tap Find Glyph in the app (diagnostic address %s:%u); BOOT starts/stops\n",
                  WiFi.localIP().toString().c_str(), Config::PORT);
    Serial.printf("[memory] queue=%u bytes DMA=%u bytes heap=%u minHeap=%u\n",
                  static_cast<unsigned>(sizeof(Packet) * Config::QUEUE_PACKETS),
                  Config::DMA_DESCRIPTORS * Config::CAPTURE_FRAMES * 8,
                  ESP.getFreeHeap(), ESP.getMinFreeHeap());
  }
  if (millis() - lastAnnouncement >= 1000) {
    lastAnnouncement = millis();
    char announcement[192];
    snprintf(announcement, sizeof(announcement),
             "{\"service\":\"glyph-voice\",\"version\":1,\"id\":\"%s\",\"port\":8080,\"path\":\"/audio\"}",
             wifiSetup.boardId.c_str());
    // On a phone hotspot, DHCP's gateway is the phone itself; no client list or multicast required.
    if (discovery.beginPacket(WiFi.gatewayIP(), 40123)) {
      discovery.write(reinterpret_cast<const uint8_t*>(announcement), strlen(announcement));
      discovery.endPacket();
    }
  }
  socketServer.loop();
  if (closeBluetooth) { closeBluetooth = false; wifiSetup.audioConnected(); }
  Packet packet;
  // A bounded batch prevents a busy audio queue starving socket housekeeping.
  for (unsigned i = 0; i < Config::SEND_BATCH_PACKETS; ++i) {
    const uint32_t epoch = connectedEpoch.load();
    if (epoch && (abortEpoch.load() == epoch || microphoneFailed.load())) {
      Serial.printf("[fault] queue=%u/%u peak=%lu maxSend=%lu ms maxCaptureGap=%lu ms dmaOverruns=%lu RSSI=%d heap=%u minHeap=%u\n",
                    static_cast<unsigned>(uxQueueMessagesWaiting(audioQueue)), Config::QUEUE_PACKETS,
                    static_cast<unsigned long>(queueHighWater.load()), static_cast<unsigned long>(maxSendMs),
                    static_cast<unsigned long>(maxCaptureGapMs.load()), static_cast<unsigned long>(microphone.overruns()),
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
    if (!sent) dropPhone("Audio socket write failed; incomplete recording discarded");
  }
  delay(1);
}
