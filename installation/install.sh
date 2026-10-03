#!/usr/bin/env bash
# Linux entry point. Run with bash; no sudo, system Python or Node required.
set -euo pipefail
glyph_here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$glyph_here/bootstrap.sh" "$@"
