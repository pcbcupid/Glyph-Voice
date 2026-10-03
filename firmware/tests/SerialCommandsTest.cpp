#include "../glyph_voice/SerialCommands.h"
#include <assert.h>
#include <string>

using Commands = SerialCommands;
Commands::Command send(Commands& parser, const std::string& line) {
  for (char value : line) assert(parser.feed(value) == Commands::None);
  return parser.feed('\n');
}

int main() {
  Commands parser;
  assert(send(parser, "HELP") == Commands::Help);
  assert(send(parser, "  help  ") == Commands::Help);
  assert(send(parser, "?") == Commands::Help);
  assert(send(parser, "status") == Commands::Status);
  assert(send(parser, " WiFi\tSetup ") == Commands::WifiSetup);
  assert(send(parser, "WIFI SETUP NOW") == Commands::Invalid);
  assert(send(parser, "RESET") == Commands::Invalid); // No destructive console commands.
  assert(send(parser, "\t  ") == Commands::None);
  for (char value : std::string("STATUS")) assert(parser.feed(value) == Commands::None);
  assert(parser.feed('\r') == Commands::Status);
  assert(parser.feed('\n') == Commands::None); // CRLF executes once.
  assert(send(parser, "HELX\bP") == Commands::Help);
  assert(send(parser, "STATUX\177S") == Commands::Status);
  assert(send(parser, std::string(1000, ' ') + "WIFI SETUP") == Commands::Invalid);
  assert(send(parser, std::string(32, 'x') + "\b\bHELP") == Commands::Invalid);
  assert(send(parser, std::string("HELP\0WIFI SETUP", 15)) == Commands::Invalid);
  assert(send(parser, std::string("\x1b[2JSTATUS")) == Commands::Invalid);
  assert(send(parser, "STATUS") == Commands::Status); // Recovers at next line.
  parser.feed('H'); parser.feed('E'); parser.clear(); // USB disconnect clears partial commands.
  assert(send(parser, "LP") == Commands::Invalid);
  assert(send(parser, "HELP") == Commands::Help);
}
