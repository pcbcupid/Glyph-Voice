# Verification — 0.11.0 / transport-r6

Release preparation date: 2026-09-30.

This page records the published 0.11.0 baseline. The subsequent **0.12.0 / transport-r7**
working-tree Bluetooth changes and current software/hardware status are documented in
[Bluetooth setup verification](BLUETOOTH_SETUP.md#verification-and-hardware-checklist).

## Software checks

- Android debug app and instrumentation APK compile successfully.
- Host regression tests: **65 passed, 3 fixture-dependent tests skipped**, zero failures/errors.
  Includes new cloud-transcription and discovery tests.
- Android lint reports no errors; existing warnings include dependency updates,
  resource/layout suggestions and the explicit battery-exemption request.
- C6 firmware compiles with Arduino ESP32 3.3.10 and WebSockets 2.7.2, USB CDC enabled,
  No OTA partition scheme. Program: 1,050,308 bytes; static RAM: 48,836 bytes.
  Runtime queue/DMA allocations are additional.
- Firmware PCM filter and stop-command host tests pass.
- Python simulator tests: **3 passed**.
- SVG circuit diagram rendered and inspected; local documentation links checked.
- Bundled model and AAR hashes are verified against pinned sources.

Final APK signing verification, artifact hashes and source revision are supplied
with the GitHub release. See [Releasing](RELEASING.md) for reproduction.

## Not performed

No physical phone or board was connected. Firmware was not flashed. Android
instrumentation tests were compiled, not executed. Actual hotspot discovery,
first-use provisioning, electrical/audio operation, locked-screen behavior,
Parakeet performance and paid cloud-provider transcription remain unverified on
hardware. Cloud client tests use a local mock server, not a provider account.

See [Testing](TESTING.md) for the hardware checklist. Photos are intentionally left
for the maintainer in [kit/photos](../kit/photos/README.md).
