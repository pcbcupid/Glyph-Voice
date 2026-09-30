#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>

// BLE provisioning v1, carried ONLY on authenticated/encrypted GATT writes.
// BEGIN [1,total], DATA [2,offset,up to 18 bytes], COMMIT [3], CANCEL [4].
// Body: [ssidBytes,passwordBytes,ssid UTF-8,password ASCII]. No terminators on wire.
struct WifiCredentials { char ssid[33]; char password[64]; };
class ProvisioningFrame {
 public:
  enum Result { More, Complete, Cancelled, Invalid };
  void reset() { memset(body, 0, sizeof(body)); expected = used = 0; }
  Result accept(const uint8_t* data, size_t size, WifiCredentials& out) {
    if (!data || !size || size > 20) return invalid();
    if (data[0] == 4 && size == 1) { reset(); return Cancelled; }
    if (data[0] == 1 && size == 2) {
      reset(); if (data[1] < 11 || data[1] > sizeof(body)) return Invalid;
      expected = data[1]; return More;
    }
    if (!expected) return invalid();
    if (data[0] == 2 && size >= 3 && data[1] == used && used + size - 2 <= expected) {
      memcpy(body + used, data + 2, size - 2); used += size - 2; return More;
    }
    if (data[0] != 3 || size != 1 || used != expected) return invalid();
    const uint8_t ssid = body[0], password = body[1];
    if (ssid < 1 || ssid > 32 || password < 8 || password > 63 || static_cast<size_t>(ssid + password + 2) != used) return invalid();
    for (unsigned i = 0; i < ssid; ++i) if (body[2 + i] == 0) return invalid();
    for (unsigned i = 0; i < password; ++i) if (body[2 + ssid + i] < 32 || body[2 + ssid + i] > 126) return invalid();
    memset(&out, 0, sizeof(out));
    memcpy(out.ssid, body + 2, ssid); memcpy(out.password, body + 2 + ssid, password);
    reset(); return Complete;
  }
 private:
  uint8_t body[97] = {};
  size_t expected = 0, used = 0;
  Result invalid() { reset(); return Invalid; }
};
