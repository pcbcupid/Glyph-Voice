// Host-only regression source; not compiled into the Arduino sketch.
#include "../glyph_voice/PcmDecimator.h"
#include <cassert>
#include <cmath>

static void constantSignal(int32_t sample, int gain, int16_t expected) {
  PcmDecimator filter;
  unsigned outputs = 0;
  for (unsigned i = 0; i < 480; ++i) {
    int16_t output = 0;
    if (filter.push(sample, gain, output)) {
      ++outputs;
      if (i >= 97) assert(output == expected);
    }
  }
  assert(outputs == 160);
}

static double toneRms(double hz) {
  PcmDecimator filter;
  double squares = 0;
  unsigned count = 0;
  for (unsigned i = 0; i < 4800; ++i) {
    const auto input = static_cast<int32_t>(1048576 * std::sin(2 * 3.141592653589793 * hz * i / 48000));
    int16_t output = 0;
    if (filter.push(input, 1, output) && i >= 480) {
      squares += static_cast<double>(output) * output;
      ++count;
    }
  }
  return std::sqrt(squares / count);
}

int main() {
  constantSignal(0, 2, 0);
  constantSignal(1048576, 2, 8192);
  constantSignal(-1048576, 2, -8192);
  constantSignal(8388607, 2, INT16_MAX);
  constantSignal(-8388608, 2, INT16_MIN);
  const double speech = toneRms(1000);
  assert(speech > 2800 && speech < 3000);
  assert(toneRms(12000) < speech * 0.01); // Reject frequencies that would alias.
}
