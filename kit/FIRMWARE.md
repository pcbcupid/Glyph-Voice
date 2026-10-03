# Firmware installation

The **transport-r11** source includes the three-second BOOT choice, **personal
hotspot name/password prompts over serial**, GPIO14 Wi-Fi LED, and USB commands.
An instructor can compile it once and
share the resulting `.bin`; participants do **not** need Arduino IDE or Python.
Older [release downloads](https://github.com/pcbcupid/Glyph-Voice/releases) may not
include these changes. Check the firmware banner, not just the filename.

| File | Purpose |
| --- | --- |
| `glyph_voice.ino.merged.bin` | Complete **merged** 4 MB image: flash at **0x0**; use this for first-time workshop setup |
| `glyph_voice.ino.bin` | Application only: flash at **0x10000** only when matching bootloader/partitions are already installed |
| `SHA256SUMS` (if supplied by the distributor) | SHA-256 checksums of the exact shared files |

Source builds place these files in the chosen Arduino build directory. This
checkout's r11 build is in `.tools/firmware-workshop/`; the general build command
in the firmware guide uses `.tools/firmware-release/`. Neither directory is tracked
by Git. Do not mistake the small `.bootloader.bin` for the complete image.

## Browser-only flash and Serial Monitor (no Arduino IDE)

Use desktop **Chrome or Edge** with a USB data cable and the trusted
[Espressif browser flasher](https://espressif.github.io/esptool-js/).
Its [official documentation](https://espressif.github.io/esptool-js/docs/index.html)
describes the Web Serial browser support. This is a desktop workshop flow, not an
iPhone/Safari flashing flow. Loading the online tool initially needs internet;
the board's configuration hotspot does not.

1. Close other serial monitors/flasher tabs. Only one program can own the USB port.
2. In **Program**, connect and select your Glyph's USB serial port. The chip should
   be detected as **ESP32-C6**. If another tool asks for a chip, choose ESP32-C6,
   not ESP32/C3/S3; no Arduino board package is needed. If it cannot
   enter flashing mode, hold BOOT, tap RESET, release BOOT, then retry.
3. Select the **merged** `.bin`, set **Flash Address to `0x0`**, then program it.
   Keep the image's flash settings/defaults. This image contains matching
   bootloader and No OTA partitions. **It can replace saved Wi-Fi and other flash
   data**; plan to reconfigure afterwards. Never put the app-only file at `0x0`.
4. Disconnect the programming session. The Espressif example Console currently
   displays logs but does not provide interactive input. Open the write-capable
   [Chrome Labs Serial Terminal](https://googlechromelabs.github.io/serial-terminal/)
   in another tab. Connect at **115200 baud**, 8 data bits, no parity, 1 stop bit,
   no hardware flow control. Turn **Local echo OFF** and **Flush on enter ON**.
5. Reset with BOOT **released**. You may need to select the port again. Watch
   **3...2...1**. Tap BOOT for setup, or do nothing to use saved Wi-Fi. A new board
   enters setup automatically. Follow the serial name/password prompts below.

Opening a USB monitor late does not prevent configuration. The firmware reports
its current mode/IP when USB connects. With a monitor that supports sending text,
send **`HELP`**, **`STATUS`** or **`WIFI SETUP`**, ending the command with Newline/Enter
(LF, CR or CRLF). Commands are case-insensitive. **A read-only monitor is not
enough for r11 naming: use the interactive terminal above.** During naming,
input is an answer, not a command. Router passwords belong in the web page only.

- `HELP`: print the command list and startup instructions again.
- `STATUS`: show the board ID, setup instructions or recording IP, signal strength,
  app/recording state and memory diagnostics. It does not print router passwords.
- `WIFI SETUP`: start hotspot naming/setup without needing to time a button
  press. **Disconnect Glyph in Android/web first**; it refuses while an app is
  connected. It does not erase the previous saved network or interrupt a recording.

For Linux port-permission errors, use your distribution's USB serial permission
setup (often the `dialout` or `uucp` group, followed by signing out/in). Check a
data-capable cable, close other port users and reconnect before suspecting firmware.

## Alternative: command-line flash

Install Espressif's `esptool` in a Python environment, connect the C6 by USB and
identify its port. The following uses esptool 5 command spelling:

```sh
python -m pip install esptool
python -m esptool --chip esp32c6 --port /dev/ttyACM0 write-flash 0x0 glyph_voice.ino.merged.bin
```

Replace `/dev/ttyACM0` with your board's port (for example `COM5` on Windows).
Flashing the complete merged image replaces flash contents, including saved Wi-Fi
configuration. Do not flash the app-only file at 0x0. If normal auto-reset fails,
hold BOOT, tap RESET, release BOOT, then retry flashing. Reset with BOOT released
for normal operation.

## First-use Wi-Fi setup

The following applies to current transport-r11 source builds. First choose
**board hotspot** credentials in serial; then enter **router/phone Wi-Fi**
credentials in the web page. These are two different networks.

1. Open USB serial at **115200 baud**, then power/reset with BOOT released.
   Tap BOOT during the printed **three-second countdown** to open setup. Without
   a tap, it uses the latest saved Wi-Fi. A board with no saved network automatically
   enters the serial naming wizard. There is no typing timeout.
2. Answer **STEP 1/2**: a board label, e.g. `Alice` or `Kit12`, then Enter.
   Use **1–19 letters/numbers/hyphens/underscores**, no spaces. Answer **STEP 2/2**:
   a private hotspot password (**8–63 printable ASCII characters**), then Enter.
   Retype to confirm. Case and spaces matter. Firmware does not echo passwords;
   keep terminal local echo off. Do not reuse an account/router password.
   The board saves this profile and creates **GLYPH-Alice-865690**, for example,
   within a few seconds. The six-character hardware suffix distinguishes boards
   using the same label; match your own serial output. Join this exact Wi-Fi using
   **your chosen password**, not `glyphvoice`. Stay connected despite “No internet.”
3. Open **http://192.168.4.1/** if the setup page does not open automatically.
4. Enter your **router or phone hotspot's** 2.4 GHz Wi-Fi name (1–32 UTF-8 bytes) and password
   (8–63 printable ASCII characters), then **Save and restart**. For a trusted
   passwordless network, explicitly select **Open network (no password)** instead.
   WPA2 Personal is recommended; 5 GHz-only, enterprise and captive-login networks
   are not supported by this setup flow. If the same phone is providing the target hotspot,
   leave setup Wi-Fi and enable its **2.4 GHz / WPA2 hotspot immediately**.
5. The board checks the connection for up to 30 seconds. On success it saves settings,
   prints the recording IP and restarts into normal mode. Leave BOOT released.
   On failure the setup hotspot remains available; rejoin it and correct the settings.
   A failed attempt does not overwrite the last saved network.
6. Copy the `[ready]` recording IP from USB serial. In Android or web, open
   **Connect Glyph**, enter that IP (optionally `:8080`) and connect.

Only setup uses the fixed setup-page address. Normal app connection uses the
manually entered recording IP; update it after DHCP changes. Credentials are stored on the board;
the public firmware contains no maintainer hotspot password.

To change credentials, reset and **tap BOOT during the three-second countdown**.
The existing alternative also works: disconnect audio clients and hold BOOT for
**5 seconds after normal startup**. Join its setup network and save new settings.
Or use the **WIFI SETUP** serial command. A failed saved-network connection keeps
retrying; it does not erase credentials or unexpectedly switch to setup mid-use.
Do not hold BOOT across reset: that enters the chip's flashing
mode instead. On subsequent setup, Enter keeps your previous hotspot label/password;
typing new values replaces them. Forgotten hotspot password? Enter a replacement
over USB. Naming is stored separately from router Wi-Fi; the stable board ID never
changes. USB access can reconfigure a board; there is no owner authentication.
The setup hotspot and web service are off during normal audio operation. NVS is
not encrypted by this prototype; configure on a trusted network near your board.

### GPIO14 status LED

- **Blinking:** firmware is running but no router Wi-Fi IP yet (countdown, naming,
  setup portal, joining or disconnected).
- **Steady on:** Wi-Fi connected with an IP. This does **not** mean the app/model
  is ready; wait for Connected in the app before recording.

The LED is active-high, configurable in `config.h`, not a recording indicator.
No LED pattern is promised while the chip is in ROM flashing mode.

### What the Serial Monitor tells you

The current banner contains **`transport-r11`** and **`128-slot queue`**. `[boot]` lines show the countdown
and chosen mode. `[setup]` prompts for your hotspot profile, then prints its name,
the URL, ordered instructions, a success/failure result and restart
directions. `[ready]` prints the final `IP:8080` for both Android and web, Wi-Fi
signal/channel and one-client guidance. `[network]` retries explain how to
reconfigure. `[audio]`, `[stream]` and `[fault]` remain available for diagnostics.
The **30-second Wi-Fi join attempt** and **3-second restart delay** are setup-only;
neither adds a recording duration limit.

## Build from source

See [firmware source and build settings](../firmware/README.md). Use the exact C6
board, USB CDC enabled, No OTA partition layout, Arduino ESP32 core 3.3.10 and
WebSockets 2.7.2. Firmware has no OTA updater; update over USB.
