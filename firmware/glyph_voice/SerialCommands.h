#pragma once
#include <stddef.h>
#include <string.h>

// USB console input must not block the audio sender or allocate unbounded Strings.
// Never echo input: users sometimes paste credentials into the wrong window.
class SerialCommands {
 public:
  enum Command { None, Help, Status, WifiSetup, Invalid };
  Command feed(char value) {
    if (value == '\r' || value == '\n') {
      if (discard) { clear(); return Invalid; }
      while (length && buffer[length - 1] == ' ') --length;
      buffer[length] = '\0';
      const char* command = buffer;
      while (*command == ' ') ++command;
      Command result = Invalid;
      if (!*command) result = None;
      else if (!strcmp(command, "HELP") || !strcmp(command, "?")) result = Help;
      else if (!strcmp(command, "STATUS")) result = Status;
      else if (!strcmp(command, "WIFI SETUP")) result = WifiSetup;
      clear();
      return result;
    }
    if (discard) return None;
    if (value == '\b' || value == 127) { if (length) buffer[--length] = 0; return None; }
    if (value == '\t') value = ' ';
    if (value < 32 || value > 126 || length >= sizeof(buffer) - 1) {
      discard = true; return None; // Reject the WHOLE line, not a command suffix.
    }
    if (value >= 'a' && value <= 'z') value -= 'a' - 'A';
    buffer[length++] = value;
    return None;
  }
  void clear() { memset(buffer, 0, sizeof(buffer)); length = 0; discard = false; }
 private:
  char buffer[32] = {};
  size_t length = 0;
  bool discard = false;
};
