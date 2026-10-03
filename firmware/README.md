# GLYPH C6 voice firmware — transport-r11 / personal hotspot setup

Matching firmware for GLYPH VOICE Android and web, using manual IP connection.
Bluetooth is removed entirely: no BLE initialization, advertising, pairing or
shutdown when audio connects. UDP discovery is removed too. Configure Wi-Fi through
the startup BOOT web portal, then copy the recording IP from USB serial. No private
hotspot credentials are compiled in. See the [workshop guide](../docs/WORKSHOP.md).

- [Kit wiring and hardware operation](../kit/HARDWARE.md)
- [Download, flashing and first-use setup](../kit/FIRMWARE.md)
- [Manual connection](../kit/CONNECTION.md)
- [Audio protocol](../docs/PROTOCOL.md)

**No Arduino IDE for participants:** share the merged `.bin` and follow the
[browser flash + Serial Monitor guide](../kit/FIRMWARE.md#browser-only-flash-and-serial-monitor-no-arduino-ide).

## Build

Use **Arduino ESP32 3.3.10**, **WebSockets by Markus Sattler 2.7.2**, board
**Pcbcupid GLYPH C6**, **USB CDC On Boot enabled**, and **No OTA (2MB APP/2MB SPIFFS)**.
The GPIO16/17 microphone wiring conflicts with UART0, so keep USB CDC enabled.

```sh
arduino-cli core update-index --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core install esp32:esp32@3.3.10 --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli lib install 'WebSockets@2.7.2'
arduino-cli compile --fqbn esp32:esp32:Pcbcupid_GLYPH_C6:CDCOnBoot=cdc,PartitionScheme=no_ota --build-path .tools/firmware-release firmware/glyph_voice
```

For Arduino IDE, open `glyph_voice/glyph_voice.ino` with all sibling headers/source
files. Select those same settings. `secrets.h` is no longer used or required.

### If Arduino reports “Sketch too big” / maximum 1310720 bytes

That limit is the board's default **1.25 MiB application partition**, not its
total 4 MB flash. Older Wi-Fi + BLE builds exceeded that limit. Continue using the
**No OTA (2MB APP/2MB SPIFFS)** layout specified above for this Wi-Fi-only build;
there is no need to change partitions or erase saved settings when upgrading.

In Arduino IDE, select **Tools → Partition Scheme → No OTA (2MB APP/2MB SPIFFS)**,
keep **Flash Size → 4MB (32Mb)** and **USB CDC On Boot → Enabled**, then rebuild
and upload normally over USB. The reported program maximum should become
**2097152 bytes**. Do not select the similarly named
**No OTA (1MB APP/3MB SPIFFS)** option.

This changes flash allocation, not audio quality or RAM buffers.
Firmware does not implement OTA updates, so no existing update feature
is lost. Use a normal Arduino USB upload to install the matching partition table;
an app-only binary cannot update an old partition layout. Keep **Erase All Flash
Before Sketch Upload** disabled to avoid unnecessarily clearing saved Wi-Fi and
other NVS data. Back up any filesystem data before changing partitions.

The build produces `glyph_voice.ino.merged.bin` (complete image, flash at 0x0),
`glyph_voice.ino.bin` (app only, at 0x10000 with matching partitions), bootloader
and partition files. Release names and flashing commands are in the Kit guide.
Prefer a normal Arduino USB upload for upgrades that should preserve saved Wi-Fi.
A full merged image can overwrite NVS with padding even without a separate erase
command; after flashing that image, be prepared to configure Wi-Fi again.

## Operation

- At application startup, USB serial prints a **3 → 2 → 1** countdown. Tap BOOT
  during those three seconds to enter the serial hotspot-naming wizard. Otherwise
  the board connects using its latest saved credentials. A new board with no saved
  network opens setup automatically. No audio recording starts during this window.
- For setup, use a **write-capable** serial monitor (115200, local echo off).
  Choose a label (1–19 ASCII letters/numbers/-/_), a private hotspot password
  (8–63 printable ASCII characters), and confirm it. The board creates
  **GLYPH-label-xxxxxx**, with its hardware suffix. Join using your chosen password
  and open **http://192.168.4.1/**. Enter the **router/phone hotspot's** credentials
  in that page, not the board hotspot's. The label is restricted to safe characters
  and SSIDs are at most 32 bytes. There is no prompt timeout.
  Select **Save and restart**: the board tests the network for up to 30 seconds,
  saves only on successful connection, displays/prints its recording IP, then
  restarts after three seconds. Leave BOOT released after restart to use that network.
  On failure the hotspot stays available for retry and previous saved settings remain.
  AP channel changes while joining can briefly disconnect the setup browser;
  rejoin the same setup hotspot or check USB serial. Setup requires no internet.
  Enter any supported 2.4 GHz personal network name; there is no hardcoded SSID.
  WPA2 Personal is recommended. Passwordless Wi-Fi needs an explicit **Open network**
  selection. 5 GHz-only, enterprise and captive-login networks are not supported.
- Tap BOOT **after** firmware startup, not while powering/resetting the chip:
  holding GPIO9 low across reset can enter the ROM flashing bootloader instead.
  Open serial at 115200 and reset with BOOT released to see the countdown.
- BOOT GPIO9 is debounced click-to-toggle: click once to start, once to stop.
- The phone can issue `STOP <recording-id>`; only a matching active ID stops capture.
- Native I2S captures 48 kHz, 24-bit left-channel samples in 32-bit stereo slots.
  A 97-tap low-pass filter decimates to 16 kHz PCM16, with 2× saturating digital gain.
- Audio sends as 1280-byte / 40 ms messages over `ws://<board>:8080/audio`. Final
  partial packets precede the end frame and exact 64-bit byte count.
- Twelve 10 ms DMA blocks and 128 output packets provide bounded buffering. Genuine
  sample loss, queue overflow or socket write failure interrupts instead of silently
  reporting incomplete audio as complete. No recording duration cutoff is imposed.
- Capture and network tasks are separate. Network writes do not run in the capture
  task or ISR. USB diagnostics are nonblocking and contain no audio or transcript.
  During recording, `[stream] still sending` reports audio/byte totals, queue depth,
  worst send time, RSSI and heap every 30 seconds. A normal stop prints whether it
  came from BOOT or the app's STOP command. Keep these lines and any `[fault]` /
  `[network]` lines when diagnosing a stalled transcription.
- On Wi-Fi reconnect, the WebSocket server restarts and prints the current recording
  IP. Update the manually entered app address if DHCP changed it. The stable board
  label is its station MAC without separators; no discovery traffic is sent.
- `WifiSetup.h` owns SoftAP setup, persisting credentials in NVS only after a
  successful join. Hold BOOT for five seconds with no audio client to reconfigure,
  or tap it during the startup countdown. HTTP setup keeps the temporary WPA2 AP
  alive until successful save/restart. `WifiCredentials.h` preserves the existing
  97-byte `wifi-v2` record, so saved networks survive a normal firmware upgrade.
  `HotspotProfile.h` holds a separate 84-byte `ap-v1` record and the bounded,
  case-preserving serial wizard. Enter can keep previous hotspot values; new
  passwords require confirmation and are never echoed. A MAC suffix distinguishes
  similar participant labels; the short suffix is not authentication.
  Wi-Fi setup remains usable even if microphone initialization fails.
- Serial at 115200 is also a bounded, nonblocking command console: **HELP**, **STATUS**,
  **WIFI SETUP** + Enter (CR/LF/CRLF). On USB attachment, the current state is printed
  again so opening a browser monitor after boot does not lose setup instructions.
  `WIFI SETUP` requires no connected app and never erases the saved network. Input
  is not echoed. **Only during the naming prompts** does serial accept board
  hotspot credentials. Router credentials belong in the web page. CRLF cannot
  advance two prompts; control characters/overflow reject the entire line.
  USB output drops rather than blocking the audio sender.
- **GPIO14 LED (active-high):** blinks 400 ms on/off without a router Wi-Fi IP;
  stays on once connected with an IP. It is not an app/model-ready or recording
  indicator. Polarity is configurable. No delays or extra capture-task work.

Wi-Fi sleep is disabled. One app client is supported. There is no remote-start,
OTA, SD audio storage, or speech model on the C6. See `config.h` for pin and buffer
constants and [Hardware](../kit/HARDWARE.md) for microphone assumptions.

### Network-stall tolerance (not an unlimited outage buffer)

The latest `maxSend=4104 ms` exceeds the previous 64 × 40 ms queue's 2560 ms
capacity. `sessionDmaOverruns=0` and `maxCaptureGap=11 ms` show capture kept up;
the sender was blocked. Good `RSSI=-39` does not prove reliable delivery or identify
whether the router, receiver scheduling, TCP retransmission or application caused
the pause. Earlier 1402 ms stalls exceeded the original 48 × 20 ms queue too.

After BLE removal, transport-r10 uses 128 × 40 ms slots: 5120 ms total capacity,
about 5040 ms with two control slots reserved. This covers the reported 4104 ms
pause if existing backlog is small. It drains up to eight
packets per loop after the link recovers. This is headroom, **not a playback delay**:
each packet sends as soon as available; it does not wait for the queue to fill.
PCM remains mono 16 kHz / 16-bit, with no compression or speech samples dropped.

The queue consumes 167,424 bytes (~164 KiB) of dynamically allocated RAM, 83,712
bytes more than r9 (not included in Arduino's static-RAM summary). The reported
155,088-byte previous minimum heap suggests ~71 KB remaining with this increase,
but that is an estimate, not a measured r10 hardware minimum. Check `[memory]` and
`[fault]` logs on your board. `sessionDmaOverruns` now distinguishes overruns during
this recording from the lifetime `dmaOverruns` total. A prolonged stall or link
slower than the 32 KB/s PCM rate can still overflow; the app reports interruption
instead of silently losing words. This change is compiled and queue-budget-tested,
but needs a real-board test on the affected network.

Successful sends taking at least 200 ms produce rate-limited `[network] Slow send`
diagnostics (elapsed time, queue backlog, RSSI and heap). `[stats]` counts them.
If they recur, keep the web tab/computer awake, check competing traffic and compare
another trusted router/hotspot. Update/restart the Python server too: its model
IPC now runs off the network event loop. This removes a blocking risk, not proof
that IPC caused this board's stall. No sample dropping or forced recording timeout
is used to make a failing connection look successful.

## Host checks

```sh
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/PcmDecimatorTest.cpp -o /tmp/glyph-decimator-test
/tmp/glyph-decimator-test
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/StopCommandTest.cpp -o /tmp/glyph-stop-test
/tmp/glyph-stop-test
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/BootSetupWindowTest.cpp -o /tmp/glyph-boot-window-test
/tmp/glyph-boot-window-test
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/AudioQueueBudgetTest.cpp -o /tmp/glyph-queue-test
/tmp/glyph-queue-test
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/SerialCommandsTest.cpp -o /tmp/glyph-serial-test
/tmp/glyph-serial-test
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/WifiCredentialsTest.cpp -o /tmp/glyph-wifi-credentials-test
/tmp/glyph-wifi-credentials-test
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/HotspotProfileTest.cpp -o /tmp/glyph-hotspot-test
/tmp/glyph-hotspot-test
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/StatusLedTest.cpp -o /tmp/glyph-led-test
/tmp/glyph-led-test
```

Compilation and host checks do not establish microphone electrical compatibility,
hotspot routing or real-time performance. This build has not been flashed or
radio-tested here; use the [physical checks](../docs/TESTING.md) before deployment.

Workshop r11 build checked on 2026-10-02: C6 build uses **1,065,944 bytes (50%)**,
static RAM **48,836 bytes (14%)** (queue memory is additional, allocated at runtime).
The linked image has no BLEDevice/NimBLE/controller initialization symbols.
Not flashed to hardware here. Before the workshop verify:

1. Saved network, no BOOT tap → countdown ends, joins saved Wi-Fi, prints recording IP.
2. Tap BOOT during countdown → serial label/password/confirmation → unique setup
   hotspot and web page; a continued hold
   must not repeatedly reboot while the portal is open.
3. Valid new settings → successful join, NVS save, automatic restart, then uses the
   new network with no tap. Confirm a subsequent power cycle also uses it.
4. Wrong password/unavailable network → setup stays available for correction;
   resetting without BOOT still uses the previous saved settings.
5. Two boards nearby → match each USB ID, hotspot suffix and page ID before setup.
6. Verify the boot banner says `transport-r11: personal hotspot + GPIO14 Wi-Fi LED; no Bluetooth`
   and `40ms audio / 128-slot queue`. Record several minutes
   on the previously failing network, then stop and check `[stats]`. If it fails,
   preserve the entire `[fault]` line, including `sessionDmaOverruns` and `maxSend`.
7. After manual IP connection, BOOT still toggles recording and app STOP still works.
   There should be no BLE advertising, pairing prompt or BLE shutdown log. Confirm
   a normal firmware upgrade preserved the last saved Wi-Fi without erasing NVS.
8. Open/reopen a browser USB monitor after startup: current setup URL/IP is printed.
   Send `HELP`, `STATUS`, `WIFI SETUP` with LF/CRLF. Setup is refused while Android/web
   is connected; it works after disconnect, including when saved Wi-Fi is unavailable.
9. Explicit open-network setup works on a trusted passwordless AP; a missing password
   without that checkbox is rejected. Protected Wi-Fi still requires its password.
10. Flash through the browser flasher, disconnect it and open the write-capable
    browser Serial Terminal at 115200. Complete setup without Arduino IDE. Check
    LF/CRLF, invalid names/password confirmation, Enter-to-keep and rename/reboot.
11. Verify GPIO14 blinking before Wi-Fi IP, steady after connection, blinking
    again on loss. Hardware polarity and the browser USB flow still need physical
    checks on the workshop computers. The audio capture/transport path is unchanged
    from r10; this change does not claim to fix sustained network backpressure.
