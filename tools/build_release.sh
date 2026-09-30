#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${GLYPH_KEYSTORE:?Set GLYPH_KEYSTORE to your private signing keystore}"
: "${GLYPH_STORE_PASSWORD:?Set the keystore password in the environment}"
: "${GLYPH_KEY_PASSWORD:?Set the key password in the environment}"
: "${GLYPH_KEY_ALIAS:?Set the signing key alias}"
version=0.12.0
output="${GLYPH_RELEASE_DIR:-.tools/release}"
build_tools="$ANDROID_HOME/build-tools/35.0.0"
python3 tools/fetch_assets.py
./gradlew :core:test :app:assembleRelease :app:lintRelease
arduino-cli compile --fqbn esp32:esp32:Pcbcupid_GLYPH_C6:CDCOnBoot=cdc,PartitionScheme=no_ota \
  --build-path .tools/firmware-release firmware/glyph_voice
mkdir -p "$output"
"$build_tools/zipalign" -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$output/aligned.apk"
"$build_tools/apksigner" sign --ks "$GLYPH_KEYSTORE" --ks-key-alias "$GLYPH_KEY_ALIAS" \
  --ks-pass env:GLYPH_STORE_PASSWORD --key-pass env:GLYPH_KEY_PASSWORD \
  --out "$output/glyph-voice-$version.apk" "$output/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$output/glyph-voice-$version.apk"
cp .tools/firmware-release/glyph_voice.ino.merged.bin "$output/glyph-voice-c6-$version.bin"
cp .tools/firmware-release/glyph_voice.ino.bin "$output/glyph-voice-c6-$version-app.bin"
(
  cd "$output"
  sha256sum "glyph-voice-$version.apk" "glyph-voice-c6-$version.bin" "glyph-voice-c6-$version-app.bin" > SHA256SUMS
)
printf 'Release files: %s\n' "$output"
