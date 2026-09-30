#pragma once

#include <atomic>
#include "esp_attr.h"
#include "driver/i2s_std.h"
#include "config.h"
#include "PcmDecimator.h"

class I2SMicrophone {
 public:
  esp_err_t begin();
  // Returns up to PCM_FRAMES_PER_READ mono PCM16 samples at SAMPLE_RATE.
  esp_err_t read(int16_t* pcm, size_t& frames);
  uint32_t overruns() const { return overruns_.load(std::memory_order_relaxed); }

 private:
  static bool IRAM_ATTR onOverflow(i2s_chan_handle_t, i2s_event_data_t*, void* ctx);
  i2s_chan_handle_t channel_ = nullptr;
  std::atomic<uint32_t> overruns_{0};
  int32_t raw_[Config::CAPTURE_FRAMES * 2] = {};
  PcmDecimator decimator_;
};
