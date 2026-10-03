#!/usr/bin/env bash
# Can also be run from Terminal: bash installation/install-macos.command
set -euo pipefail
glyph_here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$glyph_here/bootstrap.sh" "$@"
