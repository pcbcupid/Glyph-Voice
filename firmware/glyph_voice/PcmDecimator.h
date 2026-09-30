#pragma once

#include <stdint.h>

// Stateful 48 kHz -> 16 kHz low-pass decimator. No allocation or floating point.
// Keep feeding idle samples too, so packet/button boundaries never reset the
// filter or decimation phase. The 97-tap linear-phase filter delays audio by 1 ms.
class PcmDecimator {
 public:
  // Input is signed 24-bit PCM, output signed 16-bit PCM. Returns true once per
  // three input samples; gain is applied after filtering, before quantization.
  bool push(int32_t sample, int gain, int16_t& output) {
    history_[next_] = sample;
    if (++next_ == TAPS) next_ = 0;
    if (++phase_ != 3) return false;
    phase_ = 0;

    int64_t sum = 0;
    unsigned index = next_;
    for (unsigned tap = 0; tap < TAPS; ++tap) {
      if (index == 0) index = TAPS;
      --index;
      sum += static_cast<int64_t>(history_[index]) * COEFFICIENTS[tap];
    }
    // Q15 coefficients + 24-to-16-bit conversion = 23 fractional bits.
    // 64-bit arithmetic safely covers full-scale input and the configured gain.
    int64_t value = (sum * gain) / (1LL << 23);
    if (value > INT16_MAX) value = INT16_MAX;
    if (value < INT16_MIN) value = INT16_MIN;
    output = static_cast<int16_t>(value);
    return true;
  }

 private:
  static constexpr unsigned TAPS = 97;
  // Hamming-windowed sinc: fc=7000/48000 cycles/sample, center=48.
  // h[n]=sinc(2*fc*(n-48))*2*fc*(0.54-0.46*cos(2*pi*n/96)).
  // Normalize to unity DC, round to Q15, adjust center so sum is exactly 32768.
  // This filters before decimation; simply keeping every third sample aliases
  // high-frequency microphone energy into the speech band.
  static constexpr int16_t COEFFICIENTS[TAPS] = {
      0, -14, -18, -8, 11, 25, 20, -4, -32, -39, -12, 33,
      62, 43, -21, -83, -88, -15, 90, 140, 78, -67, -186, -169,
      0, 205, 275, 120, -172, -375, -294, 60, 435, 512, 158, -412,
      -753, -512, 245, 989, 1061, 185, -1187, -2020, -1285, 1320, 5023, 8275,
      9570, 8275, 5023, 1320, -1285, -2020, -1187, 185, 1061, 989, 245, -512,
      -753, -412, 158, 512, 435, 60, -294, -375, -172, 120, 275, 205,
      0, -169, -186, -67, 78, 140, 90, -15, -88, -83, -21, 43,
      62, 33, -12, -39, -32, -4, 20, 25, 11, -8, -18, -14, 0
  };
  int32_t history_[TAPS] = {};
  unsigned next_ = 0;
  unsigned phase_ = 0;
};
