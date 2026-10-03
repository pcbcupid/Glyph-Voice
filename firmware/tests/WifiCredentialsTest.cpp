#include "../glyph_voice/WifiCredentials.h"
#include <assert.h>
#include <string>

bool valid(const std::string& ssid, const std::string& password, bool open = false) {
  return validWifiCredentials(ssid.data(), ssid.size(), password.data(), password.size(), open);
}
int main() {
  assert(sizeof(WifiCredentials) == 97); // Existing NVS stays binary compatible.
  assert(valid("Workshop Wi-Fi", "password"));
  assert(valid(std::string(32, 's'), std::string(63, 'p')));
  assert(!valid("", "password"));
  assert(!valid(std::string(33, 's'), "password"));
  assert(!valid("Workshop", "1234567"));
  assert(!valid("Workshop", std::string(64, 'p')));
  assert(valid("Workshop", "pass word")); // Never trim intentional spaces.
  assert(valid(u8"café Wi-Fi", "password"));
  assert(!valid(std::string("hidden\0name", 11), "password"));
  assert(!valid("Workshop", std::string("pass\0word", 9)));
  assert(!valid("Workshop", "password\n"));
  assert(!valid("Workshop", "password\177"));
  assert(!valid("Workshop", u8"passwörd"));
  assert(valid("Open workshop", "", true));
  assert(!valid("Open workshop", "")); // Must explicitly opt into open Wi-Fi.
  assert(!valid("Open workshop", "password", true));
  assert(!valid("", "", true));
  assert(!validWifiCredentials(nullptr, 1, "password", 8, false));
}
