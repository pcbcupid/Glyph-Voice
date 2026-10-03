#pragma once

#include <stdint.h>

namespace Config {
// User-specified wiring. L/R is an output selecting the microphone's left slot.
constexpr int MIC_LR = 16;
constexpr int MIC_WS = 17;
constexpr int MIC_SD = 23;
constexpr int MIC_SCK = 22;
constexpr int RECORD_BUTTON = 9;  // Onboard BOOT, active low.
constexpr int STATUS_LED = 14;
constexpr bool STATUS_LED_ACTIVE_HIGH = true;

constexpr uint16_t PORT = 8080;
constexpr char AUDIO_PATH[] = "/audio";

// Philips I2S, two 32-bit slots, 24-bit signed samples MSB aligned.
// Keep the microphone's working 48 kHz WS / 3.072 MHz SCK mode.
// Low-pass filter and decimate its left slot to 16 kHz before transmission.
constexpr uint32_t I2S_SAMPLE_RATE = 48000;
constexpr uint32_t SAMPLE_RATE = 16000; // Actual PCM rate advertised to the phone.
// Capture every 10 ms; group four reads per 40 ms / 1280-byte message.
// After BLE removal, spend some of the recovered RAM on transient TCP stalls.
// The observed 4104 ms send exceeds the previous 64-packet / 2.56 s queue.
// Packets are still sent immediately; this is capacity, NOT a fixed audio delay.
constexpr unsigned CAPTURE_FRAMES = I2S_SAMPLE_RATE / 100; // 10 ms, 3840-byte DMA block.
constexpr unsigned PCM_FRAMES_PER_READ = SAMPLE_RATE / 100; // 160 mono output samples.
constexpr unsigned FRAMES_PER_PACKET = SAMPLE_RATE / 25; // 1280-byte PCM payload, 40 ms.
constexpr unsigned DMA_DESCRIPTORS = 12;     // 12 x 10 ms DMA blocks (nominal span).
constexpr unsigned QUEUE_PACKETS = 128;      // Up to 5120 ms, minus control slots.
constexpr unsigned CAPTURE_TASK_PRIORITY = 5; // Above loop, below Wi-Fi/lwIP tasks.
constexpr unsigned SEND_BATCH_PACKETS = 8;   // Catch up recovered links, then service controls.
constexpr uint32_t DEBOUNCE_MS = 25;
constexpr uint32_t MIC_SETTLE_MS = 100;
constexpr int PCM_GAIN = 2;  // +6.02 dB digital gain; saturation prevents overflow.
static_assert(PCM_GAIN >= 1 && PCM_GAIN <= 16, "Gain must be between 1 and 16");
static_assert(CAPTURE_FRAMES * 8 < 4092, "I2S DMA block is too large");
static_assert(I2S_SAMPLE_RATE == 48000 && SAMPLE_RATE == 16000,
              "The fixed anti-alias filter is designed for 48 kHz to 16 kHz");
static_assert(CAPTURE_FRAMES == PCM_FRAMES_PER_READ * 3, "Expected 3:1 decimation");
static_assert(FRAMES_PER_PACKET % PCM_FRAMES_PER_READ == 0, "Packets must contain whole output blocks");
static_assert(FRAMES_PER_PACKET * 2 <= 16384, "Packet exceeds phone protocol limit");
static_assert(FRAMES_PER_PACKET * 2 + 4 <= 1460, "Keep frames below typical TCP MSS");
static_assert((QUEUE_PACKETS - 2) * FRAMES_PER_PACKET * 1000 / SAMPLE_RATE >= 5000,
              "Reserve 5 seconds of jitter headroom plus control slots");
}  // namespace Config
