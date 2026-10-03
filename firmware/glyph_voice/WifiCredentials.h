#pragma once
#include <stddef.h>

// Keep the existing NVS wifi-v2 layout, so upgrading preserves saved Wi-Fi.
struct WifiCredentials { char ssid[33]; char password[64]; };
static_assert(sizeof(WifiCredentials) == 97, "Saved Wi-Fi layout must remain compatible");

// Validate bytes, not HTML character counts. Empty passwords require an explicit
// open-network choice, so a missing password never silently downgrades security.
inline bool validWifiCredentials(const char* ssid, size_t ssidBytes,
                                 const char* password, size_t passwordBytes, bool openNetwork) {
  if (!ssid || !password || ssidBytes == 0 || ssidBytes > 32) return false;
  for (size_t i = 0; i < ssidBytes; ++i) if (ssid[i] == '\0') return false;
  if (openNetwork) return passwordBytes == 0;
  if (passwordBytes < 8 || passwordBytes > 63) return false;
  for (size_t i = 0; i < passwordBytes; ++i) {
    const unsigned char value = static_cast<unsigned char>(password[i]);
    if (value < 32 || value > 126) return false;
  }
  return true;
}
