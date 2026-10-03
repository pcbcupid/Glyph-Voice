# Testing GLYPH VOICE (current source / firmware r11)

## Workshop setup verification — 2026-10-02

- r11 ESP32-C6 build succeeded: 1,065,944 bytes program, 48,836 bytes static RAM.
  `.tools/firmware-workshop/` contains merged/app binaries and verified SHA256SUMS.
- Eight C++ firmware host test executables passed, including serial hotspot
  validation/confirmation/CRLF handling and LED state logic.
- 21 installer/launcher tests passed. Linux bootstrap installed private Python
  and Node, reused/verified the model, built web, and reran successfully. Backend
  wheels also installed in a fresh managed-Python 3.13 environment.
- 31 server/summary tests passed in that fresh environment, including real
  Parakeet fixture inference, silence and authenticated loopback inference.
  Cloud provider responses in summary tests are mocked, not paid API calls.
- Web: 75 unit and 32 Chromium browser tests passed; production build passed.
- Android debug/instrumentation APK builds and lint passed; unchanged core host
  tests were up-to-date. Instrumentation APK **built**, not executed on a phone.
- `start.sh --no-install --no-browser --port 8768` served HTTP 200 and was stopped
  after the smoke check. It used the private Node runtime without a global PATH edit.

Not done: flashing this r11 image onto the user's board; checking LED polarity,
USB browser naming/portal end-to-end on real hardware; native Windows/macOS
installer execution; site MDX preview in the separate website repo; room-scale
Wi-Fi performance. New cross-platform CI jobs are defined, not remotely executed
by this local check. The audio pipeline is unchanged from r10.

## Automated software checks

Installer/launcher regression checks (Python 3.12+):

```sh
python -m unittest installation.test_setup tools.test_start_web -v
```

CI includes Linux/Windows/macOS unit checks, shell/PowerShell syntax checks and
software-only installer runs (`--skip-model`, no repeated 501 MB model download).
New CI jobs are defined here; a local run does not mean those remote jobs have run.
Rehearse each actual OS installer on a clean writable checkout, then rerun it
to check reuse, then launch with `--no-install`. Also test paths with spaces,
interrupted downloads, invalid model folders and no administrator access.

```sh
python3 tools/fetch_assets.py
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Host tests cover audio framing/counts, long streams, stop acknowledgments, queue
bounds, interruption/history coordination, summaries, manual endpoint validation,
cloud multipart requests, exact WAV headers, chunk/tail handling, HTTPS validation,
redirect rejection, response limits and cancellation. Legacy real-model fixture
tests are skipped unless their external fixtures are supplied; a host test pass
does not measure Android Parakeet inference or a paid cloud provider.

Run the C++ filter/stop tests from [firmware/README.md](../firmware/README.md) and
compile for the pinned C6 board settings. `:app:assembleDebugAndroidTest` only
builds instrumentation tests. Execute `:app:connectedDebugAndroidTest` when a test
phone is connected, taking care that those tests use disposable app data.

Web/server checks and the opt-in seven-minute WebSocket transport soak are documented
in [server/README.md](../server/README.md#checks). The soak uses synthetic audio and
no speech model. Web tests additionally advance a virtual clock through ten minutes
of PCM; Android session tests consume thirty minutes of samples faster than real time.
Both exercise explicit end handling and pause/resume without automatic completion.
These tests are not a physical-board, locked-phone or long-running model benchmark.

## Physical kit acceptance checklist — r11 setup not yet hardware-validated

Software tests/builds do not prove the new setup flow on USB/radio hardware. On the actual kit:

1. Verify supply voltage, all GPIO connections, microphone I2S format and signal
   amplitude. Check silence/noise and clipping with loud speech.
2. Flash the merged image. Verify first-use setup AP, invalid form handling,
   hotspot configuration, restart, three-second startup setup window and five-second
   BOOT reconfiguration while no audio client is connected.
   Also test desktop browser flashing then a **write-capable** browser serial
   terminal, without Arduino IDE. Enter a custom label, password and confirmation.
   Test CR/LF/CRLF, invalid/overlong input, mismatched passwords, Enter-to-keep,
   late USB connection, disconnect halfway through an answer, forgotten-password
   replacement, rename/reboot and unique suffixes for identical labels.
   No password should appear in output; turn terminal local echo off. Open the
   monitor after boot and check recovered state/IP plus HELP/STATUS commands.
   WIFI SETUP must refuse while an app is connected and work after disconnect.
   Verify open-network opt-in, protected-password validation and preservation of
   previous credentials on failure. Host parser tests do not verify radio/USB hardware.
   Check GPIO14 blinks before a Wi-Fi IP, stays on while joined, and blinks again
   on loss; app disconnection alone must not turn a Wi-Fi-ready LED off.
3. Install the APK. Confirm the launcher label is GLYPH VOICE. Confirm local mode
   is the default and cloud settings can be skipped.
4. Turn mobile data off; enable the configured 2.4 GHz hotspot. Enter the board IP,
   prepare the local model, and transcribe English without internet.
5. Restart the hotspot so DHCP changes the board address. Confirm the last entered
   IP is remembered but editable; use the latest serial IP. Test two boards without
   cross-connecting participants. Confirm no Bluetooth/location permissions, radar,
   picker or UDP discovery. Test representative Samsung/Pixel/other target phones.
6. Verify BOOT start/stop, phone stop, short final words, several minutes of
   speech, silence, rapid button presses, screen lock and background switching.
   Also run 15–30 minutes of local transcription with the screen unlocked on web;
   on Android repeat while locked with the foreground service active. Confirm the
   board's `[stream]` byte totals keep growing, no unrequested `[audio] Stop` occurs,
   and automatic summary happens only after a clean explicit end. A five-second PCM
   pause should warn, not fabricate an end. Actual network loss must still interrupt.
7. Disconnect Wi-Fi mid-recording. Confirm interrupted text survives, no automatic
   summary is sent, and reconnect requires a fresh recording.
8. Configure a compatible cloud URL/key/model after installation. With internet
   enabled, verify 15-second chunk updates and a final short chunk. Check expected
   multilingual behavior with the chosen provider. Confirm no local model loads.
9. Test cloud 401/429, offline mode, wrong URL/response, slow responses and cancel.
   Confirm previous returned text survives and no automatic retry or local fallback
   occurs. Observe provider billing/retention; these are real audio uploads.
10. Change URL with a blank key; confirm the previous key is not sent. Remove the
    key. Switch back to local while disconnected and transcribe offline again.
11. Verify conversations, summaries, deletion/copy, app restart, and an APK upgrade
    signed with the matching certificate preserve intended history.

Record phone/Android version, firmware/app versions, endpoint contract, observed
latency and failures. Do not publish API keys, hotspot passwords or private speech.
