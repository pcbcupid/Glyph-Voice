#pragma once
#include <stdint.h>

// Pure startup selector so boundary/debounce behavior can be tested without a board.
class BootSetupWindow {
 public:
  static constexpr uint32_t WINDOW_MS = 3000;
  static constexpr uint32_t DEBOUNCE_MS = 25;
  enum Choice { Waiting, ConfigureWifi, SavedWifi };
  BootSetupWindow(uint32_t now, bool pressed)
      : began(now), changed(now), rawPressed(pressed) {}
  Choice update(uint32_t now, bool pressed) {
    if (choice != Waiting) return choice;
    if (now - began >= WINDOW_MS) return choice = SavedWifi;
    if (pressed != rawPressed) { rawPressed = pressed; changed = now; }
    if (rawPressed && now - changed >= DEBOUNCE_MS) choice = ConfigureWifi;
    return choice;
  }
 private:
  uint32_t began, changed;
  bool rawPressed;
  Choice choice = Waiting;
};
