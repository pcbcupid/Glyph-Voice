# Building and publishing a release

## Tools and assets

Use JDK 17, Android SDK 36 / Build Tools 35.0.0, Python 3.11+, and the firmware
versions/settings in [firmware/README.md](../firmware/README.md). Run
`python3 tools/fetch_assets.py` to download and verify the model/AAR needed by a
fresh source checkout. Both are bundled in release APKs, not committed to Git.

## Signing

`tools/build_release.sh` requires these environment variables:

- `ANDROID_HOME`: Android SDK directory.
- `GLYPH_KEYSTORE`: private signing keystore path.
- `GLYPH_KEY_ALIAS`: signing alias.
- `GLYPH_STORE_PASSWORD` and `GLYPH_KEY_PASSWORD`: keystore/key passwords.
- Optional `GLYPH_RELEASE_DIR`: output directory (default `.tools/release`).

Set secret variables privately in your shell or secret manager; never commit them.
Run `bash tools/build_release.sh`. It runs host tests, builds/lints a release APK,
compiles firmware, aligns/signs/verifies the APK and creates SHA256SUMS. Release
APKs are non-debuggable. Increase `versionCode` and `versionName` in `app/build.gradle`
and the release script/firmware banner for each future version.

The first public release preserves the pre-existing development certificate so
previous APKs signed on the maintainer's machine can update. The maintainer's
private `.tools/signing/glyph-voice-release.p12` stores that key under alias
`glyph-voice`; its private password file is alongside it. Back up both securely
outside this checkout. Neither signing file nor GitHub CLI credentials are tracked.
Future public APKs must use the same certificate to update existing installs.
Forks should generate their own keys and choose their own application ID.

## Artifacts

- `glyph-voice-0.11.0.apk`: signed Android release with bundled local model.
- `glyph-voice-c6-0.11.0.bin`: merged C6 firmware, flash at 0x0.
- `glyph-voice-c6-0.11.0-app.bin`: app-only image, flash at 0x10000 only with matching partitions.
- `SHA256SUMS`: hashes of those binary files.
- `glyph-voice-wiring.svg`: editable circuit/wiring diagram.
- `THIRD_PARTY_NOTICES.md`: component licenses and source references.

Source is available under the release tag. The source contains no `secrets.h`,
private signing material, API keys, transcripts, recordings or machine-specific
configuration. The model's separate license and notices remain inside the APK.

## Publish

After checks, commit the source and tag the same revision used to build artifacts.
Use GitHub Releases or the GitHub CLI to attach the output files and release notes.
Authenticate CLI with your own maintainer account; SSH authentication alone does
not authenticate release API uploads. Do not paste API tokens in shell history.

Mark physical tests honestly. This release was prepared without attached hardware;
its notes must distinguish passing software checks from unrun phone/board checks.
The included [CI workflow](../.github/workflows/checks.yml) runs host/firmware checks;
private signing remains a maintainer-controlled step.
