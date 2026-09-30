# Firmware installation

Download the files for the same version from [Releases](https://github.com/pcbcupid/Glyph-Voice/releases).

| File | Purpose |
| --- | --- |
| `glyph-voice-c6-0.11.0.bin` | Complete **merged** 4 MB image: flash at **0x0** |
| `glyph-voice-c6-0.11.0-app.bin` | Application only: flash at **0x10000** only when matching bootloader/partitions are already installed |
| `SHA256SUMS` | SHA-256 checksums |

## Flash the merged image

Install Espressif's `esptool` in a Python environment, connect the C6 by USB and
identify its port. The following uses esptool 5 command spelling:

```sh
python -m pip install esptool
python -m esptool --chip esp32c6 --port /dev/ttyACM0 write-flash 0x0 glyph-voice-c6-0.11.0.bin
```

Replace `/dev/ttyACM0` with your board's port (for example `COM5` on Windows).
Flashing the complete merged image replaces flash contents, including saved Wi-Fi
configuration. Do not flash the app-only file at 0x0. If normal auto-reset fails,
hold BOOT, tap RESET, release BOOT, then retry flashing. Reset with BOOT released
for normal operation.

## First-use Wi-Fi setup

1. Power/reset the board. With no stored hotspot configuration it creates
   **GLYPH-Setup-xxxxxx**, where the suffix identifies the board.
2. Temporarily disable your phone hotspot and join that setup Wi-Fi. Password:
   **glyphvoice**. Stay connected even if Android says it has no internet.
3. Open **http://192.168.4.1/** if the setup page does not open automatically.
4. Enter your phone's hotspot name and password (8–63 bytes), then Save.
5. The board restarts. Leave the setup Wi-Fi and enable the phone hotspot in
   **2.4 GHz / WPA2 compatibility mode**.
6. Open the Android app → **Connect Glyph → Find Glyph**.

Only setup uses the fixed setup-page address. Normal app connection automatically
finds the board, including after DHCP changes. Credentials are stored on the board;
the public firmware contains no maintainer hotspot password.

To change credentials, turn the hotspot off, wait for the board to disconnect,
then hold BOOT for **5 seconds after normal startup**. Join its setup network and
save new settings. Do not hold BOOT across reset: that enters the chip's flashing
mode instead. Setup uses a shared initial password; configure it near your own
phone. The setup service is not active during normal hotspot operation.

## Build from source

See [firmware source and build settings](../firmware/README.md). Use the exact C6
board, USB CDC enabled, No OTA partition layout, Arduino ESP32 core 3.3.10 and
WebSockets 2.7.2. Firmware has no OTA updater; update over USB.
