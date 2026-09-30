#pragma once

#include <stdint.h>

namespace Config {
// User-specified wiring. L/R is an output selecting the microphone's left slot.
constexpr int MIC_LR = 16;
constexpr int MIC_WS = 17;
constexpr int MIC_SD = 23;
constexpr int MIC_SCK = 22;
constexpr int RECORD_BUTTON = 9;  // Onboard BOOT, active low.

constexpr uint16_t PORT = 8080;
constexpr char AUDIO_PATH[] = "/audio";

// Philips I2S, two 32-bit slots, 24-bit signed samples MSB aligned.
// Keep the microphone's working 48 kHz WS / 3.072 MHz SCK mode.
// Low-pass filter and decimate its left slot to 16 kHz before transmission.
constexpr uint32_t I2S_SAMPLE_RATE = 48000;
constexpr uint32_t SAMPLE_RATE = 16000; // Actual PCM rate advertised to the phone.
// Capture every 10 ms but combine two reads into one 20 ms WebSocket message.
// This halves frame/write overhead without changing the audio or wire format.
constexpr unsigned CAPTURE_FRAMES = I2S_SAMPLE_RATE / 100; // 10 ms, 3840-byte DMA block.
constexpr unsigned PCM_FRAMES_PER_READ = SAMPLE_RATE / 100; // 160 mono output samples.
constexpr unsigned FRAMES_PER_PACKET = SAMPLE_RATE / 50; // 640-byte PCM payload, 20 ms.
constexpr unsigned DMA_DESCRIPTORS = 12;     // 12 x 10 ms DMA blocks (nominal span).
constexpr unsigned QUEUE_PACKETS = 48;       // Up to 960 ms, minus control slots.
constexpr unsigned CAPTURE_TASK_PRIORITY = 5; // Above loop, below Wi-Fi/lwIP tasks.
constexpr unsigned SEND_BATCH_PACKETS = 4;   // Service ping/close between short batches.
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
}  // namespace Config
