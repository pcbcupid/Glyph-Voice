# GLYPH VOICE

**Voicing your thoughts** — a PCB Cupid GLYPH C6 microphone kit, native Android app
and self-hosted React web app.
Click the board's BOOT button to start speaking; click again to finish. The phone
connects to the board's manually entered IP and saves the transcript locally.

```text
I2S microphone → GLYPH C6 → local Wi-Fi ┬→ Android → local Parakeet → text
                                       └→ browser + your computer's Parakeet server → text

Optional, separate features: cloud STT or cloud text summaries (explicit setup).
```

[**Kit guide**](kit/README.md) · [**Download APK and firmware**](https://github.com/pcbcupid/Glyph-Voice/releases)
· [Circuit diagram](hardware/glyph-voice-wiring.svg) · [Firmware](firmware/README.md)

**Choose your setup:** Android runs the bundled model on the phone. The
[React web app](web/README.md) runs local Parakeet on **your own computer/server**,
not inside the phone/iPhone browser. Web is foreground-only and does not replace
Android's background service. AI summaries use a separate optional provider relay.

**Current source (transport-r11): Wi-Fi only, manual IP.** Bluetooth/radar setup and
automatic discovery have been removed from firmware, Android and web. Configure
Wi-Fi through the startup BOOT portal, then copy the board IP from Serial Monitor
into **Connect Glyph**. See the [workshop quick start](docs/WORKSHOP.md).
Wait through **3...2...1** to reuse saved Wi-Fi; tap BOOT during that window for a
new setup: USB serial asks for your board hotspot label and password, then creates
e.g. **GLYPH-Alice-865690**. Join it and enter your router credentials in the page.
GPIO14 **blinks without a Wi-Fi IP** and **stays on once connected**.
The [browser flash/serial guide](kit/FIRMWARE.md#browser-only-flash-and-serial-monitor-no-arduino-ide)
lets participants use a shared `.bin` without installing Arduino IDE.
Build/flash the updated firmware and apps; older published binaries do not include this change.

## Get started

**Workshop participants: [start here](docs/WORKSHOP.md).** Download/extract the
source ZIP (Git is optional), use the instructor's current firmware/APK, and follow
the browser-only flash/serial instructions. The same firmware works for all boards;
each participant names/configures theirs. No Arduino IDE is needed.

### Web: one installation command

Run in the extracted project folder while online:

| Computer | Install once | Start each time |
| --- | --- | --- |
| Linux x86_64 | `bash installation/install.sh` | `bash start-web.sh` |
| Windows x64 | `installation\install.cmd` | `start-web.cmd` |
| macOS Intel/Apple Silicon | `bash installation/install-macos.command` | `bash start-web.sh` |

Installs **project-local Python, Node, backend/web packages and English Parakeet**.
No Arduino tools, system PATH changes or admin privileges. Allow at least 5 GB
storage; the model is a ~501 MB download. [Details and troubleshooting](installation/README.md).
`bash start.sh` is also a Unix alias; the existing start-web launcher is retained.

Start opens **http://localhost:8765**. Keep its terminal open, copy its private
server token into Local model settings, select/load the installed model, then
connect to your board's serial IP. Wait for readiness before BOOT. Local speech
works without internet after preparation. The computer and foreground page must
stay awake. Phone/iPhone LAN access needs the explicit [server setup](server/README.md#phonelan-and-optional-glyph-bridge),
not `localhost` on the phone. One active recording per computer/server.

### Android

The following steps describe current source builds. Historical release images may
have different connection screens. For web use, follow the [workshop guide](docs/WORKSHOP.md).

1. Wire the kit using the [connection diagram](kit/HARDWARE.md).
2. Flash **transport-r11 firmware** using the [browser guide](kit/FIRMWARE.md).
3. In write-capable USB serial (115200, local echo off), choose a **board hotspot
   label/password** and confirm it. Join the printed `GLYPH-name-suffix` network
   with that password, then open `http://192.168.4.1` and save your **router/phone
   hotspot's** Wi-Fi details. The board checks them before saving/restarting.
4. Leave the setup network and join your target Wi-Fi. If using that phone's own
   hotspot, enable it immediately in **2.4 GHz / WPA2 compatibility mode**.
5. Install the rebuilt APK. Copy the board's recording IP from Serial Monitor
   (115200 baud), open **GLYPH VOICE → Connect Glyph**, enter the IP and connect.
6. Complete the notification/background prompts. Local mode prepares the bundled
   English Parakeet model on the phone, without a download.
7. Click **BOOT** once and speak. Click again, or use **Stop & summarize**, to finish.

See [connection and troubleshooting](kit/CONNECTION.md) for changing hotspots,
reconnecting after DHCP changes, and background recording.

## Optional cloud speech-to-text

After installation, open the right drawer → **Speech recognition · Local / Cloud**.
Disconnect first, enable cloud mode, then enter a full HTTPS transcription URL,
speech model ID, and optional API key. Local mode remains the default.

The endpoint must accept OpenAI-compatible multipart audio transcription requests.
Cloud mode sends 15-second WAV chunks while recording and a final short chunk at
stop; text updates after each response. It does not provide word-by-word realtime
streaming. Internet, endpoint availability, and any provider charges apply.
See [cloud setup and privacy](kit/APP.md#optional-cloud-transcription).

**Summaries are separate:** the existing **Connect your API** option configures
DeepSeek/OpenAI text summaries. Once its key is configured, clean completed
transcripts are summarized automatically. It does not configure speech recognition.

## Requirements

- PCB Cupid **GLYPH C6**, compatible I2S microphone module, USB power, and short wires.
- Android **8.0+**, **64-bit ARM64** phone (x86_64 is also included for development).
- Local speech: at least **2 GB free storage** for APK/model preparation and enough
  free RAM for Parakeet Unified English 0.6B INT8. Actual speed varies by phone.
- Cloud speech: internet on the hotspot phone and a compatible provider endpoint.
  Selecting cloud skips loading/extracting the local model; the APK still includes it.

## Repository

| Directory | Contents |
| --- | --- |
| [`app/`](app/) | Native Android UI, manual connection, settings and background service |
| [`core/`](core/) | Audio transport, local/cloud recognition interfaces and host tests |
| [`firmware/`](firmware/) | GLYPH C6 Arduino source and firmware host tests |
| [`hardware/`](hardware/) | Editable SVG circuit/wiring diagram and connection table |
| [`kit/`](kit/) | Hardware, app, firmware, connection and photo documentation |
| [`kit/photos/`](kit/photos/) | Folder reserved for actual kit photos |
| [`tools/`](tools/) | Pinned asset retrieval, release build and simulator tools |
| [`installation/`](installation/) | Windows/Linux/macOS web installers and setup guide |
| [`web/`](web/) / [`server/`](server/) | React frontend and private local-model/summary backend |
| [`docs/WORKSHOP.md`](docs/WORKSHOP.md) | Participant walkthrough for Android and web |
| [`docs/website/glyph-voice.mdx`](docs/website/glyph-voice.mdx) | Updated website guide, ready for maintainers to publish |

## Build the app

Use JDK 17, Android SDK 36, Build Tools 35.0.0 and Python 3.11+.

```sh
git clone git@github.com:pcbcupid/Glyph-Voice.git
cd Glyph-Voice
python3 tools/fetch_assets.py
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Windows: use `gradlew.bat`. The first build downloads dependencies. Large model
and AAR files are **not in Git**: `fetch_assets.py` downloads pinned upstream
artifacts, verifies SHA-256, and reproduces the bundled model archive. Installed
release APKs already contain these assets and work offline in local mode.
See [model provenance](docs/MODEL.md), [release build instructions](docs/RELEASING.md),
and [third-party notices](THIRD_PARTY_NOTICES.md).

## Verification status

Current **r11 workshop setup** adds personal hotspot serial configuration and the
LED without changing the working r10 audio pipeline. Firmware build and host tests,
installer tests, web and Android checks are separate from physical validation.
The new browser-flash → serial naming → Wi-Fi → recording flow and native
Windows/macOS installation still need rehearsal on workshop hardware. No board
was reflashed for this change. See [testing](docs/TESTING.md). Historical
[verification notes](docs/VERIFICATION.md) describe older releases, not r11 acceptance.
Neither these docs nor the website draft publish new release binaries automatically.

Project source uses the repository's [MIT license](LICENSE). Bundled dependencies
and the NVIDIA model retain their own licenses. No private hotspot credentials,
API keys, or signing keystores are included in source or release assets.
