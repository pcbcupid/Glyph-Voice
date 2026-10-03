#pragma once
#include <stddef.h>
#include <string.h>
#include <stdio.h>

struct HotspotProfile { char label[20]; char password[64]; };
static_assert(sizeof(HotspotProfile) == 84, "Stable ap-v1 NVS format");
inline bool validHotspotLabel(const char* text) {
  const size_t n = strlen(text);
  if (!n || n > 19) return false;
  for (size_t i = 0; i < n; ++i) {
    const char c = text[i];
    if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
          (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
  }
  return true;
}
inline bool validHotspotPassword(const char* text) {
  const size_t n = strlen(text);
  if (n < 8 || n > 63) return false;
  for (size_t i = 0; i < n; ++i) if (text[i] < 32 || text[i] > 126) return false;
  return true;
}
inline bool validHotspotProfile(const HotspotProfile& value) {
  return memchr(value.label, 0, sizeof(value.label)) && memchr(value.password, 0, sizeof(value.password))
      && validHotspotLabel(value.label) && validHotspotPassword(value.password);
}
inline void hotspotName(char (&name)[33], const HotspotProfile& profile, const char* suffix) {
  snprintf(name, sizeof(name), "GLYPH-%s-%.6s", profile.label, suffix);
}

// Separate from the command parser: labels/passwords are case-sensitive and are
// never trimmed, uppercased or echoed. CRLF must advance a prompt only once.
class SetupLineReader {
 public:
  enum Result { Waiting, Line, Invalid };
  Result feed(char c) {
    if (c == '\n' && skipLf) { skipLf = false; return Waiting; }
    skipLf = c == '\r';
    if (c == '\r' || c == '\n') { buffer[used] = 0; return bad ? Invalid : Line; }
    if (bad) return Waiting;
    if (c == '\b' || c == 127) { if (used) buffer[--used] = 0; return Waiting; }
    if (c < 32 || c > 126 || used >= sizeof(buffer) - 1) { bad = true; return Waiting; }
    buffer[used++] = c;
    return Waiting;
  }
  const char* line() const { return buffer; }
  void consumed() { memset(buffer, 0, sizeof(buffer)); used = 0; bad = false; }
  void reset() { consumed(); skipLf = false; }
  void commandEnded(char c) { reset(); skipLf = c == '\r'; }
 private:
  char buffer[64] = {};
  size_t used = 0;
  bool bad = false, skipLf = false;
};

class HotspotWizard {
 public:
  enum Stage { Name, Password, Confirm, Ready };
  enum Result { Accepted, BadName, BadPassword, Mismatch };
  void begin(const HotspotProfile& previous) {
    memset(&value, 0, sizeof(value));
    if (validHotspotProfile(previous)) value = previous;
    stage = Name;
  }
  Result submit(const char* line) {
    if (stage == Name) {
      if (!*line && validHotspotLabel(value.label)) { stage = Password; return Accepted; }
      if (!validHotspotLabel(line)) return BadName;
      strcpy(value.label, line); stage = Password;
    } else if (stage == Password) {
      if (!*line && validHotspotPassword(value.password)) { stage = Ready; return Accepted; }
      if (!validHotspotPassword(line)) return BadPassword;
      memset(value.password, 0, sizeof(value.password));
      strcpy(value.password, line); stage = Confirm;
    } else if (stage == Confirm) {
      if (strcmp(line, value.password)) {
        memset(value.password, 0, sizeof(value.password)); stage = Password; return Mismatch;
      }
      stage = Ready;
    }
    return Accepted;
  }
  void clear() { memset(&value, 0, sizeof(value)); stage = Name; }
  HotspotProfile value = {};
  Stage stage = Name;
};
