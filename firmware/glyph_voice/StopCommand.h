#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>

// Strict bounded ASCII command, no allocation/JSON library on the capture path.
inline uint32_t parseStopCommand(const uint8_t* data, size_t length) {
  if (!data || length < 7 || length > 16 || memcmp(data, "STOP r", 6) != 0) return 0;
  if (data[6] == '0') return 0;
  uint32_t id = 0;
  for (size_t i = 6; i < length; ++i) {
    if (data[i] < '0' || data[i] > '9') return 0;
    const uint32_t digit = data[i] - '0';
    if (id > (UINT32_MAX - digit) / 10) return 0;
    id = id * 10 + digit;
  }
  return id;
}
