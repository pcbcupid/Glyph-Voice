# GLYPH VOICE Kit

Build a button-operated speech-to-text kit using a GLYPH C6, an I2S microphone,
and your Android phone **or the React web app on a computer**. The board captures sound; the phone/computer transcribes it locally
or, if you explicitly configure it, sends audio to your cloud speech provider.

![GLYPH VOICE wiring](../hardware/glyph-voice-wiring.svg)

1. [Hardware: parts, wiring and how audio travels](HARDWARE.md)
2. [Firmware: download, flash and configure Wi-Fi](FIRMWARE.md)
3. [App: install, transcribe, choose local/cloud and summarize](APP.md)
4. [Connection: manual IP and troubleshooting](CONNECTION.md)
5. [Photos folder](photos/README.md) — reserved for photos to be added by the maintainer.
6. [Workshop walkthrough: Android and web](../docs/WORKSHOP.md)
7. [Web installers: Windows, Linux, macOS](../installation/README.md)

Current firmware **r11** asks for a personal hotspot label/password over USB
serial, adds a hardware suffix, and shows Wi-Fi status on GPIO14. Flash and
configure with a desktop browser; participants need no Arduino IDE.

Use the instructor's current build, or a matching APK/firmware from [Releases](https://github.com/pcbcupid/Glyph-Voice/releases).
Older published releases may not include r11; verify the banner/release notes.
Use the release's SHA256SUMS to check downloaded files. This kit does not use the
phone's microphone, an SD card, or a cloud account in local mode.
