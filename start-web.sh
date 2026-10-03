#!/usr/bin/env bash
# Run from any directory; the shared launcher handles setup and server startup.
set -e
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
for glyph_python in .tools/web-venv/bin/python .tools/local-stt-venv/bin/python; do
  if [[ -x "$glyph_python" ]]; then
    exec "$glyph_python" tools/start_web.py "$@"
  fi
done
for glyph_python in python3 python; do
  if command -v "$glyph_python" >/dev/null 2>&1; then
    exec "$glyph_python" tools/start_web.py "$@"
  fi
done
echo "Run bash installation/install.sh (Linux) or bash installation/install-macos.command first." >&2
exit 1
