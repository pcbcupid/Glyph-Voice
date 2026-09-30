# GLYPH VOICE

**Voicing your thoughts** — a PCB Cupid GLYPH C6 microphone kit and Android app.
Click the board's BOOT button to start speaking; click again to finish. The phone
finds the board automatically on its hotspot and saves the transcript locally.

```text
I2S microphone → GLYPH C6 → phone hotspot → Android app → transcript
                                              ├─ Local Parakeet (default, offline)
                                              └─ Optional cloud speech endpoint
```

[**Kit guide**](kit/README.md) · [**Download APK and firmware**](https://github.com/pcbcupid/Glyph-Voice/releases)
· [Circuit diagram](hardware/glyph-voice-wiring.svg) · [Firmware](firmware/README.md)

## Get started

1. Wire the kit using the [connection diagram](kit/HARDWARE.md).
2. Flash the release's **merged firmware `.bin`** using the [firmware guide](kit/FIRMWARE.md).
3. On first boot, join `GLYPH-Setup-xxxxxx` (password `glyphvoice`). Open
   `http://192.168.4.1` and save your phone's hotspot name and password.
4. Leave the setup network and enable that phone hotspot in **2.4 GHz / WPA2 compatibility mode**.
5. Install the release APK, open **GLYPH VOICE**, and tap **Connect Glyph → Find Glyph**.
   No board IP address is needed. If several boards appear, tap **Choose Glyph**.
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
| [`app/`](app/) | Native Android UI, settings, discovery and background service |
| [`core/`](core/) | Audio transport, local/cloud recognition interfaces and host tests |
| [`firmware/`](firmware/) | GLYPH C6 Arduino source and firmware host tests |
| [`hardware/`](hardware/) | Editable SVG circuit/wiring diagram and connection table |
| [`kit/`](kit/) | Hardware, app, firmware, connection and photo documentation |
| [`kit/photos/`](kit/photos/) | Folder reserved for actual kit photos |
| [`tools/`](tools/) | Pinned asset retrieval, release build and simulator tools |

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

Version **0.11.0 / transport-r6** adds automatic hotspot discovery, optional cloud
transcription, and first-use firmware Wi-Fi setup. Software tests and builds are
tracked in [verification](docs/VERIFICATION.md). No phone or board was connected
during this release's preparation; physical hotspot, microphone, and phone checks
remain listed in [testing](docs/TESTING.md). No physical performance claims are made.

Project source uses the repository's [MIT license](LICENSE). Bundled dependencies
and the NVIDIA model retain their own licenses. No private hotspot credentials,
API keys, or signing keystores are included in source or release assets.
