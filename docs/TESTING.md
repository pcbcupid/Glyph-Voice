# Testing GLYPH VOICE 0.11.0

## Automated software checks

```sh
python3 tools/fetch_assets.py
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Host tests cover audio framing/counts, long streams, stop acknowledgments, queue
bounds, interruption/history coordination, summaries, discovery packet validation,
cloud multipart requests, exact WAV headers, chunk/tail handling, HTTPS validation,
redirect rejection, response limits and cancellation. Legacy real-model fixture
tests are skipped unless their external fixtures are supplied; a host test pass
does not measure Android Parakeet inference or a paid cloud provider.

Run the C++ filter/stop tests from [firmware/README.md](../firmware/README.md) and
compile for the pinned C6 board settings. `:app:assembleDebugAndroidTest` only
builds instrumentation tests. Execute `:app:connectedDebugAndroidTest` when a test
phone is connected, taking care that those tests use disposable app data.

## Physical kit acceptance checklist — not run during this release

No board or phone was connected during release preparation. On the actual kit:

1. Verify supply voltage, all GPIO connections, microphone I2S format and signal
   amplitude. Check silence/noise and clipping with loud speech.
2. Flash the merged image. Verify first-use setup AP, invalid form handling,
   hotspot configuration, restart, and five-second BOOT reconfiguration.
3. Install the APK. Confirm the launcher label is GLYPH VOICE. Confirm local mode
   is the default and cloud settings can be skipped.
4. Turn mobile data off; enable the configured 2.4 GHz hotspot. Tap Find Glyph,
   prepare the local model, and transcribe English without internet.
5. Restart the hotspot so DHCP changes the board address. Confirm rediscovery
   without entering an IP. Test two boards, chooser, remembered selection and a
   missing saved board. Test representative Samsung/Pixel/other target phones.
6. Verify BOOT start/stop, phone stop, short final words, several minutes of
   speech, silence, rapid button presses, screen lock and background switching.
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
