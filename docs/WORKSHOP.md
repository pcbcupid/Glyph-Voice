# Workshop: one participant, one Glyph, one local server

Current source builds use **Wi-Fi only and manual IP entry** on web and Android.
Bluetooth/radar setup and automatic discovery are removed. Keep the Android app
disconnected during web use. Flash transport-r11 firmware before the workshop;
updating the webpage alone cannot remove Bluetooth from an older board image.
For an upgrade, prefer normal Arduino USB upload with **Erase All Flash Before
Sketch Upload** disabled. A full merged-image flash can clear saved Wi-Fi;
reconfigure those boards using the steps below.

## Prepare before the event

- Download/extract the whole project ZIP; Git is optional. Use the
  [one-command installer](../installation/README.md) on each web participant's
  computer. It installs Python, Node, dependencies and the English Parakeet model;
  no Arduino tools. Allow 5 GB disk space. Model download is about 501 MB; prepare
  computers before everyone arrives. Android-only users skip this installer.
- Each computer runs its own server/model. The current backend supports **one
  active recording**, not a room of simultaneous participants. Do not share an
  instructor's token/server between the whole class.
- Use a trusted 2.4 GHz WPA2 workshop router that allows clients to communicate,
  or each participant's own phone hotspot with their computer and Glyph connected.
  Guest/client isolation prevents direct connections. The audio socket has no
  participant authentication; manual IP selection is not an access-control system.
- Label boards with their unique `GLYPH-xxxxxx` suffix from USB serial, not just
  their current IP. DHCP addresses can change. Reserve addresses on a shared
  router if the instructor controls it; do not assign one static IP to all boards.
- Participants can flash the instructor-provided merged `.bin` and read USB logs
  using a desktop browser; Arduino IDE is not required. See the
  [browser flashing instructions](../kit/FIRMWARE.md#browser-only-flash-and-serial-monitor-no-arduino-ide).

## First-time Wi-Fi setup (no Bluetooth)

1. Flash the instructor's **r11 merged .bin** at **0x0** using the
   [Espressif browser flasher](https://espressif.github.io/esptool-js/) (ESP32-C6),
   then disconnect the flasher. Open the write-capable
   [Chrome Labs Serial Terminal](https://googlechromelabs.github.io/serial-terminal/)
   at **115200 baud**, turn **Local echo off** and **Flush on enter on**. Reset with BOOT
   released. **Tap BOOT during the three-second countdown** for Wi-Fi setup.
   With no tap it uses the latest saved network; first-time boards open setup anyway.
2. Answer the serial prompts: a board label, e.g. **Alice** (1–19 letters/numbers/-/_),
   a private **board hotspot password** (8–63 printable ASCII characters), then
   confirm it. The board creates e.g. **GLYPH-Alice-865690** within a few seconds.
   Join that exact network using **your chosen password**, then open
   **http://192.168.4.1/** in a separate tab. All setup networks use the same IP,
   but are separate networks: match the suffix to your USB output. Passwords are
   not echoed. No typing timeout; read the prompt and answer one line at a time.
3. Enter the target router/hotspot credentials. The Glyph attempts to join for
   30 seconds and saves them only on success, then restarts into normal mode.
   Keep BOOT released on that restart. Failed setup keeps the portal available for
   retry without overwriting the previous saved network. If using the same phone for setup
   and hotspot, return to hotspot mode promptly after saving.
4. Rejoin the target Wi-Fi on your computer. **GPIO14 blinks until the board has a
   Wi-Fi IP, then stays on**. That does not mean the app/model is connected yet.
   Read the new recording IP from USB
   serial. Do not copy another participant's IP. Do not use the setup IP as the
   recording IP unless the serial output actually advertises it.
5. Already configured boards skip these steps. To reconfigure, reset and tap BOOT
   during the countdown, or disconnect audio clients and hold BOOT for five seconds
   after startup, then release. On reconfiguration, Enter keeps a previous hotspot
   label/password, or type replacements. A forgotten hotspot password can be
   replaced over USB. Holding
   BOOT across reset instead enters flashing mode.

If you missed the countdown, send **STATUS** + Enter in a write-capable USB monitor
to retrieve the current IP/instructions, or **WIFI SETUP** to enter setup after
disconnecting the app. **HELP** lists these commands. Only board-hotspot credentials
are typed into serial **when prompted**; router credentials belong in the web page.
Inside naming, input is an answer rather than a command. First-time setup, failed joins and successful saves all
have numbered/explanatory logs; previous saved settings survive a failed attempt.
For a passwordless trusted AP, explicitly select **Open network** in the setup form;
do not use enterprise/captive-login Wi-Fi or a 5 GHz-only hotspot.

The setup portal uses your chosen private hotspot password and HTTP; configure on a trusted
network near your own device. It is the only Wi-Fi configuration path; there is
no Bluetooth pairing, permission request or PIN.

## Start the web app

From the extracted project folder, install once online, then start each day:

| Computer | Install once | Start each time |
| --- | --- | --- |
| Linux x86_64 | `bash installation/install.sh` | `bash start-web.sh` |
| Windows x64 | `installation\install.cmd` | `start-web.cmd` |
| macOS Intel/Apple Silicon | `bash installation/install-macos.command` | `bash start-web.sh` |

The existing launcher remains the entry point. `bash start.sh` is also a short
alias. No Python/Node/Arduino installation by hand is required. Details, supported
OSes and offline/model-reuse options: [Installation](../installation/README.md).

For a prepared Unix checkout:

```sh
bash start-web.sh
```

On Windows, double-click `start-web.cmd`. Keep its terminal open; Ctrl+C stops
the server. `.tools` is not committed; run installation after extracting source.
The launcher opens
**http://localhost:8765** automatically (or open it yourself), then:

1. **Connect Glyph** → type your board's serial-monitor IP (port defaults to 8080).
2. **Next: speech recognition** → **Local model** → paste the server's private
   token, confirm audio processing on that computer, and choose the model folder.
3. **Load this model locally**. This now loads **and warms up** the model using
   disposable silence before connecting to the board. Do not press BOOT yet.
4. Once **Glyph connected** appears, click BOOT, speak immediately, then click
   again to stop. The received-audio counter should advance before the first words.
5. Leave the browser visible and computer awake. Refreshing clears speech settings
   and token, but committed browser transcript history and the last entered board
   IP are retained. The remembered IP is only a convenience: confirm it still
   matches your board before connecting.

There are two different IPs when opening the app on a phone: the **computer IP**
hosts the page/model server, while the **Glyph IP** is entered in Connect Glyph.
Use the LAN/HTTPS and optional `--glyph-host` bridge instructions in the server
guide. Local Parakeet runs **on that computer, not inside iPhone Safari**. The
default launcher is localhost-only; use the explicit server LAN/HTTPS setup for
phone access. Keep the page foreground and computer awake. Never port-forward
this single-user service onto the public internet.

## Android instead of web

Install the instructor's current APK (Android 8+ / 64-bit, at least 2 GB free storage).
No web installer or computer is needed for recording after board setup. Put the
phone on the configured Wi-Fi, or enable the configured phone hotspot. Open
**Connect Glyph**, enter the serial `[ready] IP:8080`, review notification/background
prompts, and let the **bundled** English model finish preparing. No model download
is needed. Wait for Connected, then BOOT once to start and again to finish.
Android uses a foreground service for background work, subject to vendor power
restrictions. See [Android guide](../kit/APP.md). Disconnect it before using web.

## History and optional summaries (both apps)

Left drawer = raw conversations; right drawer = summaries/settings. Copy uses
the displayed text. Individual entries can be deleted. Android/web histories are
separate, local to their app/browser. Don't clear browser site data to update.

**Connect your API** configures DeepSeek/OpenAI text summaries, not local speech.
Review text-sharing settings and enter your own key. Clean BOOT-stop automatically
summarizes when configured/enabled; **Stop & summarize** stops the board and waits
for final words first. Summarize without settings opens setup. Results appear in
the same text box and the summary drawer; errors keep the original. Web summaries
use the local server relay. Internet/provider charges apply, and transcript text
leaves the LAN for the selected provider. Never share a class-wide private key.

## Opening words and verification

The model has a 1.12-second context preset plus CPU/network latency; warm-up does
not promise zero-delay text. Incoming PCM is retained in a bounded queue while
stream startup/inference runs. It is never intentionally trimmed to the latest
audio to catch up. Excess backlog produces an explicit error instead of success.

Regression checks cover ten seconds of distinct opening PCM arriving before a
delayed stream-open acknowledgment, including BOOT stop during that delay. Real
speech checks can assert the fixture's opening words using `GLYPH_TEST_PREFIX`.
These checks do not replace testing your actual mic/network in the room.

Before the workshop, test two separate computer/board pairs simultaneously. Say
“first words, one two three” immediately after BOOT and verify those words survive
in the final transcript. Check the received-seconds counter: if it starts late,
inspect board/network logs; if it starts immediately but text is late, investigate
inference latency or microphone level. Keep tokens, Wi-Fi passwords and speech
out of public screenshots/logs.

## Updating an existing workshop checkout

Restart the launcher from the updated checkout; it rebuilds the web app. Disconnect
the board in the browser before accepting **Update app** (or reloading). Do not
clear browser site data: that deletes local conversation history. The connection
dialog should say **Your board. Your IP. No pairing.** and have no Bluetooth button.
Serial should print **transport-r11: personal hotspot + GPIO14 Wi-Fi LED; no Bluetooth** and
**128-slot queue**. Restart the Python server too: the model-process handoff no
longer blocks the network event loop. The larger board queue covers roughly five
seconds of transient stalls, not an indefinitely slow receiver. If backpressure
recurs, retain the `[network] Slow send` and `[fault]` lines and note whether the
browser is on the model computer or another device, and whether the tab was visible.
Do not hand out older release binaries and assume they contain this source change.

Run the checks in [Testing](TESTING.md) and rehearse with the actual workshop
network before distributing the kit. Software tests cannot verify RF reliability,
phone power management or many simultaneous clients in the room.
