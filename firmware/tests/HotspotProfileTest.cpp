#include "../glyph_voice/HotspotProfile.h"
#include <cassert>
#include <string>

int main() {
  assert(validHotspotLabel("Alice_12-A"));
  assert(!validHotspotLabel(""));
  assert(!validHotspotLabel("Alice <b>"));
  assert(!validHotspotLabel(std::string(20, 'a').c_str()));
  assert(validHotspotPassword("Keep Case !"));
  assert(!validHotspotPassword("1234567"));
  assert(!validHotspotPassword("password\t"));
  assert(!validHotspotPassword(std::string(64, 'a').c_str()));
  HotspotProfile previous = {};
  HotspotWizard wizard;
  wizard.begin(previous);
  assert(wizard.submit("") == HotspotWizard::BadName);
  assert(wizard.submit("Alice") == HotspotWizard::Accepted);
  assert(wizard.stage == HotspotWizard::Password);
  assert(wizard.submit("") == HotspotWizard::BadPassword);
  wizard.submit("Keep Case !");
  assert(wizard.stage == HotspotWizard::Confirm);
  assert(wizard.submit("keep case !") == HotspotWizard::Mismatch);
  assert(!wizard.value.password[0]);
  wizard.submit("Keep Case !"); wizard.submit("Keep Case !");
  assert(wizard.stage == HotspotWizard::Ready && validHotspotProfile(wizard.value));
  previous = wizard.value;
  char name[33]; hotspotName(name, previous, "865690");
  assert(!strcmp(name, "GLYPH-Alice-865690"));
  wizard.clear(); assert(!wizard.value.password[0]);
  wizard.begin(previous); wizard.submit(""); wizard.submit("");
  assert(wizard.stage == HotspotWizard::Ready);
  assert(!strcmp(wizard.value.password, previous.password));
  memset(previous.label, 'x', sizeof(previous.label));
  assert(!validHotspotProfile(previous));
  wizard.begin(previous); assert(!wizard.value.label[0]);
  wizard.submit(std::string(19, 'a').c_str()); wizard.submit("password"); wizard.submit("password");
  hotspotName(name, wizard.value, "ABCDEF"); assert(strlen(name) == 32);
  SetupLineReader reader;
  reader.commandEnded('\r'); // WIFI SETUP\r\n must not skip the name prompt.
  assert(reader.feed('\n') == SetupLineReader::Waiting);
  for (char c : std::string("AaX\b!")) assert(reader.feed(c) == SetupLineReader::Waiting);
  assert(reader.feed('\r') == SetupLineReader::Line);
  assert(!strcmp(reader.line(), "Aa!")); reader.consumed();
  assert(!reader.line()[0]);
  assert(reader.feed('\n') == SetupLineReader::Waiting);
  for (int i=0; i<64; ++i) reader.feed('x');
  assert(reader.feed('\n') == SetupLineReader::Invalid); reader.consumed();
  reader.feed(0); assert(reader.feed('\n') == SetupLineReader::Invalid); reader.reset();
  assert(reader.feed('\n') == SetupLineReader::Line); // Explicit Enter keeps existing values.
}
