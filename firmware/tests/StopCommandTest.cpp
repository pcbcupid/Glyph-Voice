#include "../glyph_voice/StopCommand.h"
#include <assert.h>

int main() {
  assert(parseStopCommand(reinterpret_cast<const uint8_t*>("STOP r1"), 7) == 1);
  assert(parseStopCommand(reinterpret_cast<const uint8_t*>("STOP r4294967295"), 16) == UINT32_MAX);
  const char* invalid[] = {"", "STOP", "STOP r0", "STOP r01", "STOP r-1", "STOP r4294967296",
                          "STOP r1\n", "START r1", "STOP r1 extra", "STOP r99999999999"};
  for (const char* command : invalid)
    assert(parseStopCommand(reinterpret_cast<const uint8_t*>(command), strlen(command)) == 0);
  assert(parseStopCommand(nullptr, 7) == 0);
}
