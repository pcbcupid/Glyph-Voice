# GLYPH C6 voice firmware — transport-r6 / 0.11.0

Matching firmware for the GLYPH VOICE Android app, with automatic hotspot discovery
and first-use Wi-Fi configuration. No private hotspot credentials are compiled in.

- [Kit wiring and hardware operation](../kit/HARDWARE.md)
- [Download, flashing and first-use setup](../kit/FIRMWARE.md)
- [Connection and discovery](../kit/CONNECTION.md)
- [Audio and discovery protocol](../docs/PROTOCOL.md)

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

The build produces `glyph_voice.ino.merged.bin` (complete image, flash at 0x0),
`glyph_voice.ino.bin` (app only, at 0x10000 with matching partitions), bootloader
and partition files. Release names and flashing commands are in the Kit guide.

## Operation

- BOOT GPIO9 is debounced click-to-toggle: click once to start, once to stop.
- The phone can issue `STOP <recording-id>`; only a matching active ID stops capture.
- Native I2S captures 48 kHz, 24-bit left-channel samples in 32-bit stereo slots.
  A 97-tap low-pass filter decimates to 16 kHz PCM16, with 2× saturating digital gain.
- Audio sends as 640-byte / 20 ms messages over `ws://<board>:8080/audio`. Final
  partial packets precede the end frame and exact 64-bit byte count.
- Twelve 10 ms DMA blocks and 48 output packets provide bounded buffering. Genuine
  sample loss, queue overflow or socket write failure interrupts instead of silently
  reporting incomplete audio as complete. No recording duration cutoff is imposed.
- Capture and network tasks are separate. Network writes do not run in the capture
  task or ISR. USB diagnostics are nonblocking and contain no audio or transcript.
- On Wi-Fi reconnect, the WebSocket server and UDP announcer restart. Every second,
  announcements go directly to the hotspot gateway on UDP 40123. The stable board
  ID is its station MAC without separators. No mDNS/client-list lookup is needed.
- `WifiSetup.h` owns first-use setup and saves hotspot settings in NVS. Its temporary
  WPA2 setup AP/web page closes on restart into normal operation. Hold BOOT for
  five seconds while disconnected, after normal boot, to reconfigure it.

Wi-Fi sleep is disabled. One app client is supported. There is no remote-start,
OTA, SD audio storage, or speech model on the C6. See `config.h` for pin and buffer
constants and [Hardware](../kit/HARDWARE.md) for microphone assumptions.

## Host checks

```sh
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/PcmDecimatorTest.cpp -o /tmp/glyph-decimator-test
/tmp/glyph-decimator-test
c++ -std=c++17 -Wall -Wextra -Werror firmware/tests/StopCommandTest.cpp -o /tmp/glyph-stop-test
/tmp/glyph-stop-test
```

Compilation and host checks do not establish microphone electrical compatibility,
hotspot routing or real-time performance. No hardware was attached during this
release preparation; use the [physical checks](../docs/TESTING.md) before deployment.
