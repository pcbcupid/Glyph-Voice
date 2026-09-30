#include "../glyph_voice/ProvisioningFrame.h"
#include <assert.h>
#include <string>
#include <vector>

static ProvisioningFrame::Result transfer(ProvisioningFrame& frame, WifiCredentials& out,
                                        const std::string& ssid, const std::string& password) {
  std::vector<uint8_t> body{static_cast<uint8_t>(ssid.size()), static_cast<uint8_t>(password.size())};
  body.insert(body.end(), ssid.begin(), ssid.end()); body.insert(body.end(), password.begin(), password.end());
  const uint8_t begin[] = {1, static_cast<uint8_t>(body.size())};
  if (frame.accept(begin, 2, out) == ProvisioningFrame::Invalid) return ProvisioningFrame::Invalid;
  for (size_t offset = 0; offset < body.size(); offset += 18) {
    std::vector<uint8_t> data{2, static_cast<uint8_t>(offset)};
    const size_t end = offset + 18 < body.size() ? offset + 18 : body.size();
    data.insert(data.end(), body.begin() + offset, body.begin() + end);
    assert(frame.accept(data.data(), data.size(), out) == ProvisioningFrame::More);
  }
  const uint8_t commit[] = {3}; return frame.accept(commit, 1, out);
}
int main() {
  ProvisioningFrame frame; WifiCredentials out;
  assert(transfer(frame, out, "Lab", "testpass") == ProvisioningFrame::Complete);
  assert(strcmp(out.ssid, "Lab") == 0 && strcmp(out.password, "testpass") == 0);
  assert(transfer(frame, out, std::string(32, 'x'), std::string(63, 'y')) == ProvisioningFrame::Complete);
  assert(strlen(out.ssid) == 32 && strlen(out.password) == 63);
  assert(transfer(frame, out, " padded ", " password ") == ProvisioningFrame::Complete);
  assert(strcmp(out.ssid, " padded ") == 0);
  assert(transfer(frame, out, std::string("x\0y", 3), "testpass") == ProvisioningFrame::Invalid);
  assert(transfer(frame, out, "Lab", "test\npass") == ProvisioningFrame::Invalid);
  assert(transfer(frame, out, "Lab", "short") == ProvisioningFrame::Invalid);
  const uint8_t begin[] = {1, 13}, wrongOffset[] = {2, 1, 3}, commit[] = {3}, cancel[] = {4}, huge[] = {1, 98};
  assert(frame.accept(begin, 2, out) == ProvisioningFrame::More);
  assert(frame.accept(commit, 1, out) == ProvisioningFrame::Invalid);
  assert(frame.accept(begin, 2, out) == ProvisioningFrame::More);
  assert(frame.accept(wrongOffset, 3, out) == ProvisioningFrame::Invalid);
  assert(frame.accept(commit, 1, out) == ProvisioningFrame::Invalid);
  assert(frame.accept(begin, 2, out) == ProvisioningFrame::More);
  assert(frame.accept(cancel, 1, out) == ProvisioningFrame::Cancelled);
  assert(frame.accept(commit, 1, out) == ProvisioningFrame::Invalid);
  assert(frame.accept(huge, 2, out) == ProvisioningFrame::Invalid);
  assert(frame.accept(nullptr, 0, out) == ProvisioningFrame::Invalid);
  assert(frame.accept(begin, 21, out) == ProvisioningFrame::Invalid);
  // Rejected and cancelled frames never mutate the last complete output.
  assert(strcmp(out.ssid, " padded ") == 0);
}
