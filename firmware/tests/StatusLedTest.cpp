#include "../glyph_voice/StatusLed.h"
#include <cassert>

int main() {
  assert(statusLedOn(0, false));
  assert(statusLedOn(399, false));
  assert(!statusLedOn(400, false));
  assert(!statusLedOn(799, false));
  assert(statusLedOn(800, false));
  for (unsigned t=0; t<10000; ++t) assert(statusLedOn(t, true));
  assert(statusLedOn(0xffffffffU, true));
}
