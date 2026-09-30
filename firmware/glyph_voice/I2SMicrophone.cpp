#include <Arduino.h>
#include "I2SMicrophone.h"

static_assert(ATOMIC_INT_LOCK_FREE == 2, "ISR counters require lock-free atomics");

bool IRAM_ATTR I2SMicrophone::onOverflow(i2s_chan_handle_t, i2s_event_data_t*, void* ctx) {
  static_cast<I2SMicrophone*>(ctx)->overruns_.fetch_add(1, std::memory_order_relaxed);
  return false;
}

esp_err_t I2SMicrophone::begin() {
  pinMode(Config::MIC_LR, OUTPUT);
  digitalWrite(Config::MIC_LR, LOW);
  i2s_chan_config_t channelConfig = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
  channelConfig.dma_desc_num = Config::DMA_DESCRIPTORS;
  channelConfig.dma_frame_num = Config::CAPTURE_FRAMES;
  esp_err_t err = i2s_new_channel(&channelConfig, nullptr, &channel_);
  if (err != ESP_OK) return err;

  i2s_std_config_t config = {};
  config.clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(Config::I2S_SAMPLE_RATE);
  config.slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_32BIT, I2S_SLOT_MODE_STEREO);
  config.gpio_cfg.mclk = I2S_GPIO_UNUSED;
  config.gpio_cfg.bclk = static_cast<gpio_num_t>(Config::MIC_SCK);
  config.gpio_cfg.ws = static_cast<gpio_num_t>(Config::MIC_WS);
  config.gpio_cfg.dout = I2S_GPIO_UNUSED;
  config.gpio_cfg.din = static_cast<gpio_num_t>(Config::MIC_SD);
  err = i2s_channel_init_std_mode(channel_, &config);
  i2s_event_callbacks_t callbacks = {};
  callbacks.on_recv_q_ovf = onOverflow;
  if (err == ESP_OK) err = i2s_channel_register_event_callback(channel_, &callbacks, this);
  if (err == ESP_OK) err = i2s_channel_enable(channel_);
  if (err != ESP_OK) {
    i2s_del_channel(channel_);
    channel_ = nullptr;
  }
  return err;
}

esp_err_t I2SMicrophone::read(int16_t* pcm, size_t& frames) {
  size_t bytes = 0;
  frames = 0;
  esp_err_t err = i2s_channel_read(channel_, raw_, sizeof(raw_), &bytes, 100);
  if (err != ESP_OK) return err;
  if (bytes == 0 || bytes % 8 != 0) return ESP_ERR_INVALID_SIZE;
  for (size_t i = 0; i < bytes / 8; ++i) {
    // Left 24-bit sample occupies bits 31..8; right slot is unused.
    // Filter at 48 kHz before 3:1 decimation, with persistent state across reads.
    int16_t output;
    if (decimator_.push(raw_[i * 2] >> 8, Config::PCM_GAIN, output)) {
      pcm[frames++] = output;
    }
  }
  return ESP_OK;
}
