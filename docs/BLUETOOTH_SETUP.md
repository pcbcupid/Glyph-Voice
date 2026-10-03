# Bluetooth setup removed

Current transport-r11 firmware and the Android/web apps use **Wi-Fi only**.
There is no BLE advertising, radar, pairing PIN, Bluetooth permission or credential
transfer over Bluetooth. This page remains only so old links explain the change.

Use [manual IP connection](../kit/CONNECTION.md) and the
[workshop guide](WORKSHOP.md). To change Wi-Fi, reset with BOOT released, tap BOOT
during the three-second countdown, answer the **board label/password prompts** in
a write-capable USB serial monitor (115200, local echo off), then join the printed
`GLYPH-name-suffix` hotspot using your chosen password. Open `http://192.168.4.1/`
and enter the **router/phone Wi-Fi** credentials. Save, let the board restart,
then use the new recording IP printed on USB serial in **Connect Glyph**.

Saved Wi-Fi uses the same `wifi-v2` NVS layout. Flash without erasing NVS to preserve
it. Updating only the app does not remove Bluetooth from an older firmware image.
