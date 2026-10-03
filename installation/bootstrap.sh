#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
glyph_root="$PWD"
case "$(uname -s)/$(uname -m)" in
  Linux/x86_64) glyph_target=x86_64-unknown-linux-gnu; glyph_sha=b9980552309f09c15172b8be828555e375097f16deb459795ce7bfd200380f0b ;;
  Darwin/x86_64) glyph_target=x86_64-apple-darwin; glyph_sha=1b8a5b316883df2daf20fb9a446e5b230e01d947d57aba2694977c5ac5a7e98c ;;
  Darwin/arm64) glyph_target=aarch64-apple-darwin; glyph_sha=5d714de09501a59393ceca78f4bc232a50478729640d251907160299b2a93ddd ;;
  *) echo 'Supported: Linux x86_64 (glibc), macOS Intel/Apple Silicon, Windows x64. See installation/README.md.' >&2; exit 1 ;;
esac
for glyph_tool in curl tar; do
  command -v "$glyph_tool" >/dev/null || { echo "Missing $glyph_tool; install it with your OS package manager first." >&2; exit 1; }
done
if ! command -v sha256sum >/dev/null && ! command -v shasum >/dev/null; then
  echo 'A SHA-256 verifier is required: install sha256sum (coreutils) or shasum first.' >&2
  exit 1
fi
glyph_boot="$glyph_root/.tools/bootstrap/uv-0.12.22"
mkdir -p "$glyph_boot"
glyph_archive="$glyph_boot/uv-$glyph_target.tar.gz"
glyph_hash() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d ' ' -f 1
  else shasum -a 256 "$1" | cut -d ' ' -f 1; fi
}
echo 'Installing web tools inside this project only. Internet required; no Arduino tools or administrator access.'
if [[ ! -f "$glyph_archive" ]] || [[ "$(glyph_hash "$glyph_archive")" != "$glyph_sha" ]]; then
  curl --fail --location --retry 3 --connect-timeout 30 --speed-limit 1024 --speed-time 120 \
    --output "$glyph_archive.part" "https://github.com/astral-sh/uv/releases/download/0.12.22/uv-$glyph_target.tar.gz"
  [[ "$(glyph_hash "$glyph_archive.part")" == "$glyph_sha" ]] || { echo 'uv checksum mismatch; nothing executed. Retry or contact your instructor.' >&2; exit 1; }
  mv -- "$glyph_archive.part" "$glyph_archive"
fi
tar -xzf "$glyph_archive" -C "$glyph_boot"
glyph_uv="$glyph_boot/uv-$glyph_target/uv"
export UV_PYTHON_INSTALL_DIR="$glyph_root/.tools/runtime/python"
export UV_CACHE_DIR="$glyph_root/.tools/uv-cache"
export UV_NO_CONFIG=1
"$glyph_uv" python install 3.13 --no-bin --no-registry --no-config
glyph_python="$("$glyph_uv" python find --managed-python 3.13)"
exec "$glyph_python" installation/setup.py "$@"
