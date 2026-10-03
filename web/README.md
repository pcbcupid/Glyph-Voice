# Glyph Voice web — foreground React app

React + TypeScript + Vite. This is a **foreground-only web preview**, kept beside
the working Android app. It is not yet a feature-equivalent replacement.

## Local models / self-hosting

**Install once:** use [installation/README.md](../installation/README.md):
Linux `bash installation/install.sh`, Windows `installation\install.cmd`, macOS
`bash installation/install-macos.command`. These provide project-local Python,
Node, web/backend dependencies and the English model. No Arduino installation.

**Start each time:** run `./start-web.sh` from the repository root (Windows:
double-click `start-web.cmd`). It prepares the web build, starts the local Python
server, and opens the browser. Python 3.12+, Node 22.12+ and an extracted model are
required (provided by the installer); missing project packages are installed on first run. See the
[launcher instructions](../server/README.md#recommended-one-launcher).

Board **r11** setup: serial hotspot label/password → personal GLYPH-name-suffix
network → router credentials at http://192.168.4.1 → recording IP in Connect Glyph.
GPIO14 blinking = no Wi-Fi IP; steady = joined, not model ready. See the
[workshop walkthrough](../docs/WORKSHOP.md) and [browser flashing guide](../kit/FIRMWARE.md).

**Local model** now runs Parakeet Unified streaming on your own Python server.
The UI lets you browse that server's model folders and load the selected model.
No cloud STT is used in this mode. This is server-local inference, **not phone/browser-local**.
The optional server bridge also lets an HTTPS page reach an allowlisted Glyph through
same-origin WSS. Follow the [complete self-hosting guide](../server/README.md) for
Python setup, models, folder selection, LAN/HTTPS, Docker and privacy boundaries.

Build with `npm run build`, then run `python -m server.app --models-dir /path/to/models`
from the repository root using the Python environment described in that guide. The
server serves this app at `http://localhost:8765`. Click **Connect Glyph**, enter
your board's serial-monitor IP, then **Next: speech recognition → Local model →
Choose model folder**, using the private server access token. Loading now includes
a disposable silent inference warm-up before the model is marked ready. The app
connects to the entered board after speech configuration succeeds.

For multiple boards in a room, follow [Workshop setup](../docs/WORKSHOP.md).

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
2. **Connect Glyph** opens manual IP setup by default, without a Bluetooth prompt.
   Enter your own board's IP from USB serial (115200 baud), then configure speech.
   The three-step connection screen remembers the last entered IP, with expandable
   Wi-Fi setup and troubleshooting help. Bluetooth/radar/pairing are removed.
   See the [workshop guide](../docs/WORKSHOP.md). Phone-only browser
   Parakeet is not ported; use the self-hosted local server or optional cloud mode.
3. For cloud speech, explicitly choose **Cloud provider**, supply an
   HTTPS multipart-transcription endpoint and model, and optionally your own key.
   The provider must permit browser CORS **from this page's origin**, including
   the Authorization header if used. Native Android success does not establish CORS
   compatibility. No provider account is included and no proxy is deployed.
4. The Glyph's private IPv4 address comes from its serial output. Port defaults to
   8080. Hosted HTTPS needs the local server's configured bridge for this implementation.
   Use transport-r8 to remove Bluetooth from the board too; protocol-v1 audio stays
   compatible with older firmware. Close the Android connection
   first: the firmware accepts only one client. Wait for **Glyph connected** before BOOT.
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
| Manual IP connection, remembered address and startup Wi-Fi portal instructions | Workshop network and real-device validation |
| Opt-in cloud audio; DeepSeek/OpenAI text summaries through the local server, auto-summary on stop and cancellation | Real provider-account validation (tests use mocks) |
| IndexedDB raw history, deletion and interruption recovery | Android history import/export/migration |
| Clipboard and internal transcript scroll with follow-end behavior | Physical Android/iPhone/Glyph verification |
| PWA shell cache, screen wake lock, offline inference with reachable local server | Browser-only offline inference without another computer |

There are no mock transcripts in the normal app, no browser SpeechRecognition API,
and no phone mic. Native Android remains unchanged by this web-summary port.

## AI summaries

If you still see **“AI summaries · next milestone”**, you are viewing the old app
shell. Stop recording, restart the launcher, close **all** Glyph tabs/installed-app
windows for this same origin, and reopen the same URL. On desktop, a hard refresh
(`Ctrl+Shift+R`) also bypasses the old cached navigation. Do **not** clear site data:
that would erase IndexedDB conversation/summary history. Future builds now show an
**Update app** prompt; disconnect Glyph and finish summaries before applying it.
Updating preserves saved history but clears tab-only credentials. The update prompt
uses the plugin's [documented prompt lifecycle](https://vite-pwa-org.netlify.app/guide/prompt-for-update.html).

Restart `./start-web.sh` (Windows: `start-web.cmd`) to rebuild the web app and run
the updated local server; refresh the browser. Open the right **Summaries** drawer
→ **Connect your API**, choose DeepSeek or OpenAI, enter your own key, and enable
text-sharing consent. When local speech is configured, its server URL/token are
prefilled; otherwise enter the local server connection here. Keys/settings stay
only in this tab's memory and must be entered again after refresh. Saving alone
does not contact a provider, except when completing a pending manual Summarize action.

- Clean BOOT-stop automatically summarizes the final transcript when configured
  (disable the automatic checkbox to opt out). **Stop & summarize** sends STOP
  to the board and waits for its end frame and final recognition first.
- **Summarize** without a key opens setup. The result replaces the main text,
  with **Show original transcript / Back to summarized**, and is saved in the
  right drawer without opening it. Both histories remain browser-local.
- The English-only prompt requests natural paragraphs, not Overview/Key Points
  headings. Output can still be inaccurate; non-Latin-script responses are rejected.
- Cancellation or deleting a source while summarizing discards late results.
  Deleting a raw entry keeps already-saved summaries and their source snapshots.
  Deleting a summary keeps the raw entry. Local deletion cannot retract provider data.
- Only one summary runs at a time; additional completed recordings remain in raw
  history for manual summarization. Failures keep the original; no automatic retry.
- Maximum source: 48 KB UTF-8; output budget: 2,048 tokens. Oversized sources are
  rejected, not truncated. These are **summary** limits, not recording limits.

Local Parakeet inference still needs no internet. **Summaries are cloud requests**:
only the selected transcript text and provider key go through your trusted backend
to the fixed provider endpoint. Internet, account/model access and provider charges
apply. The backend avoids browser provider-CORS problems; it does not eliminate
provider retention or billing. No shared key is shipped or saved on the server.
Use localhost or trusted HTTPS to protect keys in transit; LAN HTTP is unencrypted.
See [server summary security and request limits](../server/README.md#text-summary-relay).

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

Screen wake lock is requested by default while connected; its checkbox can disable
it. It is secure-context-only and may be revoked by the OS.
It keeps the screen awake when available; it does not enable locked-screen work.
On LAN HTTP, change your screen timeout manually for testing.

There is no total recording-duration timer. Five seconds without PCM shows a
waiting warning rather than disconnecting a live socket. BOOT or Stop & summarize
ends a normal recording; true socket failures, abandoned local-model sessions and
processing/resource overload still report an interruption. Current firmware has
no Bluetooth setup window or Bluetooth initialization/shutdown during recording.

## Privacy

- Nothing is sent to a cloud speech provider by default. Saving cloud settings makes no request.
- Choosing/loading a local model contacts your configured server. Local speech sends
  PCM there after recording starts; it does not use a third-party STT provider. The
  server must be on your LAN if you want audio to remain on that LAN.
- Once cloud mode is explicitly configured, recordings go directly to that endpoint.
  There is no developer backend, analytics, shared key or hidden fallback provider.
- No audio files are persisted. PCM/WAV buffers are short-lived and cleared when
  practical, but JavaScript/Fetch can make copies: this is not guaranteed secure erasure.
- Only the last validated board IP is kept in localStorage as a convenience; it
  does not trigger an automatic connection on reload. Update it if DHCP changes it.
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
Current manual-IP tests check validation, remembered/corrupted preferences,
unavailable preference storage and the absence of Bluetooth chooser calls. BLE
tests were removed together with the feature.
The local-mode browser tests mock the backend; separate real-engine and backend/bridge
checks are documented in the [server guide](../server/README.md#checks).

## Structure

```text
src/core/       Portable wire contract, types and recording session
src/network/    Manual-IP WebSocket adapter
src/speech/     Explicit cloud adapter and streaming self-hosted local-model adapter
src/data/       Browser-local history
src/ui/         Small reusable components
src/Runtime.ts  Owns connection, session and history outside React rendering
src/App.tsx     Responsive UI and foreground lifecycle policy
```
