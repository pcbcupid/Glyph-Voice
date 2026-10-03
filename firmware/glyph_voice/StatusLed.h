#pragma once
#include <stdint.h>
// No delay() and no extra work on the capture task. Polarity is configured by the caller.
inline bool statusLedOn(uint32_t now, bool wifiHasIp) {
  return wifiHasIp || ((now / 400) % 2 == 0);
}
