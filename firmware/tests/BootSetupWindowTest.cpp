#include "../glyph_voice/BootSetupWindow.h"
#include <assert.h>

int main() {
  using Window = BootSetupWindow;
  Window normal(100, false);
  assert(normal.update(3099, false) == Window::Waiting);
  assert(normal.update(3100, false) == Window::SavedWifi);
  assert(normal.update(3200, true) == Window::SavedWifi); // Late click cannot change boot mode.

  Window clicked(0, false);
  assert(clicked.update(500, true) == Window::Waiting);
  assert(clicked.update(524, true) == Window::Waiting);
  assert(clicked.update(525, true) == Window::ConfigureWifi);
  assert(clicked.update(4000, false) == Window::ConfigureWifi); // Selection latches.

  Window bounce(0, false);
  assert(bounce.update(10, true) == Window::Waiting);
  assert(bounce.update(20, false) == Window::Waiting);
  assert(bounce.update(50, false) == Window::Waiting);
  assert(bounce.update(100, true) == Window::Waiting);
  assert(bounce.update(125, true) == Window::ConfigureWifi);

  Window held(50, true); // GPIO already low when application window begins.
  assert(held.update(74, true) == Window::Waiting);
  assert(held.update(75, true) == Window::ConfigureWifi);

  Window boundary(0, false);
  assert(boundary.update(2974, true) == Window::Waiting);
  assert(boundary.update(2999, true) == Window::ConfigureWifi);
  Window tooLate(0, false);
  assert(tooLate.update(2990, true) == Window::Waiting);
  assert(tooLate.update(3000, true) == Window::SavedWifi);

  constexpr uint32_t nearWrap = UINT32_MAX - 100;
  Window wrap(nearWrap, false);
  assert(wrap.update(nearWrap + 2999, false) == Window::Waiting);
  assert(wrap.update(nearWrap + 3000, false) == Window::SavedWifi);
  Window wrapClick(nearWrap, false);
  assert(wrapClick.update(nearWrap + 90, true) == Window::Waiting);
  assert(wrapClick.update(nearWrap + 115, true) == Window::ConfigureWifi);
}
