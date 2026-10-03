#include "../glyph_voice/config.h"
#include <assert.h>
#include <deque>
void survives(unsigned stallMs, unsigned initialPackets) {
  const unsigned packetMs = Config::FRAMES_PER_PACKET * 1000 / Config::SAMPLE_RATE;
  std::deque<unsigned> queue{0}; // reserve a control frame
  unsigned produced = 1;
  for (unsigned i = 0; i < initialPackets; ++i) queue.push_back(produced++);
  // Queue-budget model of the reported stall, not a real-radio test.
  for (unsigned ms = 0; ms < stallMs; ms += packetMs) {
    queue.push_back(produced++);
    assert(queue.size() < Config::QUEUE_PACKETS - 1);
  }
  queue.push_back(produced++);
  for (unsigned expected = 0; expected < produced; ++expected) {
    assert(queue.front() == expected); queue.pop_front();
  }
  assert(queue.empty());
}
int main() {
  survives(1402, 0);
  survives(4104, 0); // Latest fault: over four seconds without a successful send.
  survives(4104, 15); // Same stall, plus 600 ms already pending.
  survives(4960, 0); // Near the new budget; reserve start and end slots.
  assert(Config::FRAMES_PER_PACKET * 2 == 1280);
  assert(Config::QUEUE_PACKETS == 128);
  // Document the old failure instead of implying a test exercises real TCP.
  const unsigned blockedPackets = (4104 + 39) / 40;
  assert(blockedPackets > 64);
  // A finite queue cannot cover an indefinitely blocked receiver. Keep that
  // failure explicit, not a ring buffer that overwrites the oldest speech.
  assert((6000 + 39) / 40 > Config::QUEUE_PACKETS);
}
