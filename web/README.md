# Glyph Voice web — foreground React app

React + TypeScript + Vite. This is a **foreground-only web preview**, kept beside
the working Android app. It is not yet a feature-equivalent replacement.

## Local models / self-hosting

**Local model** now runs Parakeet Unified streaming on your own Python server.
The UI lets you browse that server's model folders and load the selected model.
No cloud STT is used in this mode. This is server-local inference, **not phone/browser-local**.
The optional server bridge also lets an HTTPS page reach an allowlisted Glyph through
same-origin WSS. Follow the [complete self-hosting guide](../server/README.md) for
Python setup, models, folder selection, LAN/HTTPS, Docker and privacy boundaries.

Build with `npm run build`, then run `python -m server.app --models-dir /path/to/models`
from the repository root using the Python environment described in that guide. The
server serves this app at `http://localhost:8765`; use its private access token in
**Speech recognition → Local model → Choose model folder**.

## Frontend-only development / optional cloud mode

Requires Node 22.12+ and npm. No Android Studio, Xcode or Mac required.

```sh
cd web
npm ci
npm run dev
```

Open `http://localhost:5173` on the development computer. For a phone, put the
computer and board on that phone's hotspot and open `http://<computer-LAN-IP>:5173`.
Allow the development port through your computer's firewall if necessary. The
development server binds to all interfaces; use only a trusted LAN. Do not expose
it through a public tunnel or port-forward.

1. Keep this browser page visible. Only one recording tab should be open.
2. **Connect Glyph** opens radar-style setup. Supported HTTPS/localhost browsers can
   choose a Glyph over Bluetooth, securely pair and send Wi-Fi details. Use transport-r7;
   see [Bluetooth setup](../docs/BLUETOOTH_SETUP.md). On iPhone Safari or LAN HTTP, use
   the Wi-Fi setup fallback and **Enter an existing Glyph IP**. Phone-only browser
   Parakeet is not ported; use the self-hosted local server or optional cloud mode.
3. For cloud speech, explicitly choose **Cloud provider**, supply an
   HTTPS multipart-transcription endpoint and model, and optionally your own key.
   The provider must permit browser CORS **from this page's origin**, including
   the Authorization header if used. Native Android success does not establish CORS
   compatibility. No provider account is included and no proxy is deployed.
4. Enter the Glyph's private IPv4 address from its serial output. Port defaults to
   8080. BLE can supply this address automatically on localhost; hosted HTTPS still
   needs the local server's configured bridge for this implementation. Transport-r6 remains compatible for manual audio;
   transport-r7 adds Bluetooth setup. Close the Android connection
   first: the firmware accepts only one client.
5. BOOT starts recording; BOOT or **Stop recording** ends it. In cloud mode, transcript updates
   after each 15-second cloud chunk and the final tail, **not word by word**.
6. Completed and interrupted text is saved in IndexedDB and listed in the left
   drawer. Copy and delete are implemented. On HTTP, clipboard access may require
   manual copying of the selected text. Keys/configuration are not persisted and
   must be entered again after refresh.

The app disconnects and requests hardware stop when hidden. Existing words are
kept; untranscribed/in-flight audio may be lost and already sent requests may be
billed. The stop frame is best effort when a browser is being suspended; the board
also stops on socket disconnect. Reconnect and start a **new** recording on return.
There is no guarantee against an abrupt OS/process kill. Completed text checkpoints
survive when IndexedDB commits; never rely on page-unload work to persist new data.

## Implemented / not yet ported

| Implemented in this slice | Still required for replacement |
| --- | --- |
| Responsive, dark-mode UI and accessible left/right drawers | Phone-only browser Parakeet inference |
| Self-hosted streaming Parakeet and server model-folder picker | Wider local-model compatibility testing |
| Optional allowlisted same-origin Glyph bridge for HTTPS/WSS | Real TLS deployment and phone/board validation |
| Firmware v1 protocol validation, remote STOP, bounded audio and reconnect | Direct HTTPS-to-board transport without a backend |
| Web Bluetooth setup/pairing on supported browsers; Safari fallback instructions | Physical browser/OS pairing verification |
| Opt-in cloud audio, cancellation, timeout and response bounds | Text-summary providers, automatic summaries and cancellation |
| IndexedDB raw history, deletion and interruption recovery | Android history import/export/migration |
| Clipboard and internal transcript scroll with follow-end behavior | Physical Android/iPhone/Glyph verification |
| PWA shell cache, screen wake lock, offline inference with reachable local server | Browser-only offline inference without another computer |

The summary drawer and original/summary display are present as migration UI; no
summary endpoint is called, no summaries are generated, and no summary key is
collected. The UI explicitly labels this as the next milestone. There are no mock
transcripts in the normal app, no browser SpeechRecognition API, and no phone mic.

## Browser security / PWA boundary

`npm run build` produces `dist/`; `npm run preview` serves it for local inspection.
PWA installation/service workers require HTTPS or localhost (phone LAN HTTP is
neither). The shell caches only static assets. Installing it **does not** grant
background execution, offline cloud speech, or connectivity to the board.

The existing board serves plain `ws://`. For cross-browser predictability this
preview refuses to connect to that board from an HTTPS page, with an explanation.
A self-hosted HTTPS deployment can use the [local server bridge](../server/README.md#https-hosting);
direct HTTPS-to-board connections remain blocked. Do not disable browser security or treat
a self-signed device certificate as a universal solution. Browser versions differ
in local-network permissions. UDP auto-discovery cannot be reused in a normal web app.

Screen wake lock is optional, secure-context-only and may be revoked by the OS.
It keeps the screen awake when available; it does not enable locked-screen work.
On LAN HTTP, change your screen timeout manually for testing.

## Privacy

- Nothing is sent to a cloud speech provider by default. Saving cloud settings makes no request.
- Choosing/loading a local model contacts your configured server. Local speech sends
  PCM there after recording starts; it does not use a third-party STT provider. The
  server must be on your LAN if you want audio to remain on that LAN.
- Once cloud mode is explicitly configured, recordings go directly to that endpoint.
  There is no developer backend, analytics, shared key or hidden fallback provider.
- No audio files are persisted. PCM/WAV buffers are short-lived and cleared when
  practical, but JavaScript/Fetch can make copies: this is not guaranteed secure erasure.
- Keys remain in tab memory; they are never saved to localStorage/IndexedDB or
  embedded in build environment variables. Memory-only does not protect against
  XSS, browser extensions or a compromised device. Use limited, revocable personal keys.
- No HTTP redirects, credentials/cookies, referrer or automatic billable retry.
- Browser text history is local, not encrypted by this app, and may be evicted or
  removed by clearing site data. Different origins/ports have separate history.
- This app cannot read Android SQLite or Android Keystore. Android data is untouched.

## Checks

```sh
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

To use an existing Chromium binary:

```sh
CHROMIUM_PATH=/absolute/path/to/chrome npm run test:e2e
```

Browser tests use synthetic PCM, a mocked WebSocket peer and intercepted cloud
responses. Mobile viewport tests use Chromium, **not real Safari/iPhone testing**.
No real speech/model accuracy, device networking or paid requests are verified by
those tests. The existing Android and firmware checks remain separate.

Checked on 2026-09-30: **68 unit tests and 16 browser tests passed**, including
the production PWA shell reloading offline. TypeScript/production build passed;
`npm audit --omit=dev --audit-level=moderate` reported zero runtime-dependency
vulnerabilities. This is not a security audit or a hardware/provider validation.
The added BLE tests use mocked GATT/pairing and verify framing, cancellation, endpoint
validation, fallback UI and credential handoff. Actual radio/security/OS PIN dialogs
require real-device testing; a mock cannot validate those.
The local-mode browser tests mock the backend; separate real-engine and backend/bridge
checks are documented in the [server guide](../server/README.md#checks).

## Structure

```text
src/core/       Portable wire contract, types and recording session
src/network/    WebSocket adapter and optional BLE Wi-Fi provisioning
src/speech/     Explicit cloud adapter and streaming self-hosted local-model adapter
src/data/       Browser-local history
src/ui/         Small reusable components
src/Runtime.ts  Owns connection, session and history outside React rendering
src/App.tsx     Responsive UI and foreground lifecycle policy
```
