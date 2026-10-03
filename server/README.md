# Self-hosted local speech models

The React app now supports **Local model** alongside its existing optional cloud mode.
Local mode runs streaming recognition in the included Python backend on **your computer
or server**, not in the phone's browser. Android's Java/Parakeet implementation is unchanged.

```text
Glyph → local Wi-Fi → browser → your local model server → live text → browser history
```

With the optional same-origin bridge, the server also connects to the Glyph. The browser
still owns the foreground recording session and forwards PCM to the inference API.
There is no automatic cloud fallback, model download at runtime, audio storage or analytics.
If you host this on a remote VPS, audio leaves your LAN for that VPS: self-hosted does
not necessarily mean on-device or on-LAN. Use a trusted local computer for LAN-only operation.

## Requirements and model support

- Python **3.12+** with wheels available for the pinned dependencies; Node **22.12+**
  for the web build. This change was exercised on Linux x86_64 / Python 3.14.
- A CPU host with sufficient RAM for Parakeet 0.6B plus the OS (plan for 4–6 GB
  available; measure your own host). Up to four inference threads by default.
- An **extracted sherpa-onnx streaming transducer model folder**. Verified target:
  `sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-1120ms`, the same model
  bundled by the Android app. This is **not** the non-streaming or TDT export.
- Required files: `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`,
  `tokens.txt`. Float `.onnx` names and unambiguous `encoder-*.onnx` etc. are accepted
  for compatible streaming transducers; other models are **not verified**. Arbitrary
  GGUF, Whisper checkpoints, `.nemo`, or Hugging Face directories are not supported.

Obtain the selected model from the [official sherpa-onnx release](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-1120ms.tar.bz2).
Verify its archive SHA-256:

```text
4788229a6dd03be33f8243ccee48e33a8d15df7b448cb99150b0ccddd1b02d74
```

Extract it into a dedicated model collection. Alternatively, if this checkout already
has the verified Android model ZIP, extract that ZIP there; no second download is needed.
See [model provenance and license](../docs/MODEL.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).
Only load models from trusted sources. File presence is checked before loading; the
native engine then validates compatibility. A bad model fails instead of invoking cloud STT.

Example layout (the collection root is the only tree visible to the folder picker):

```text
my-models/
  parakeet-unified-streaming/
    encoder.int8.onnx
    decoder.int8.onnx
    joiner.int8.onnx
    tokens.txt
```

## Run on your computer

### Recommended: one launcher

First run the [web installer](../installation/README.md) for your computer:
Linux `bash installation/install.sh`, Windows `installation\install.cmd`, macOS
`bash installation/install-macos.command`. It supplies private Python 3.13,
Node 24.21.0, dependencies and the streaming English model, without Arduino or
system-wide installs. An existing manually prepared Python 3.12+/Node 22.12+
checkout still works. Then, from the repository folder:

```sh
./start-web.sh
```

On Windows, double-click **start-web.cmd** (or run it in a terminal). The shared
launcher can also be run as `python tools/start_web.py` using a supported Python.
Linux installation/startup was exercised; native Windows/macOS execution needs
workshop-computer validation in addition to cross-platform CI unit checks.

The launcher reuses a matching repo Python environment, or creates
`.tools/web-venv` and installs the pinned backend packages. It installs missing
web packages with `npm ci`, builds the current web app, starts the server on
**localhost:8765**, and opens your browser after the port is bound. It does not
install Python/Node system-wide or download model weights **at startup**. The
separate installation step supplies them. First-time dependency installation
requires internet; prepared installations run offline.

It detects `.tools/local-models` or `models`, or asks for your model collection
folder once. The chosen path is remembered in ignored `.tools/web-launcher.json`;
no token, Wi-Fi password or speech is saved there. To change it:

```sh
./start-web.sh --models-dir /absolute/path/to/my-models
```

Keep the launcher terminal open. Copy its private token into **Local model**
settings and choose/load the model as below. **Ctrl+C** stops the server; closing
the browser alone does not. Restarting generates a new token as before.

Optional flags: `--no-install` prohibits package installation (offline check),
`--no-browser` skips opening a browser, `--port 8766` selects another port, and
`--glyph-host 192.168.1.42` enables the allowlisted bridge. Run `./start-web.sh --help`
for details. This simple launcher stays loopback-only. For phone/LAN hosting use
the explicit server command and allowed-origin configuration below.

### Manual startup (advanced)

From the repository root:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r server/requirements.txt
cd web
npm ci
npm run build
cd ..
.venv/bin/python -m server.app --models-dir /absolute/path/to/my-models
```

On Windows use `py -3.12 -m venv .venv` (or another installed supported Python)
and `.venv\Scripts\python.exe` instead of `.venv/bin/python`. No shell activation is required.

Open **http://localhost:8765**. The backend serves `web/dist` and its API together.
It binds to loopback by default, prints a randomly generated private **server access token**,
and does not load a model until you choose one. Copy that token into the app; it is not
a cloud API key. Restarting generates a new token unless you set `GLYPH_LOCAL_TOKEN`
to a private random 24–256 character URL-safe value. Never commit or share this token publicly.

### Choose the model

1. Click **Connect Glyph**, enter your board's serial-monitor IP, then **Next: speech recognition**.
   You can also open **Speech recognition** from the right drawer to configure the model first.
2. Select **Local model** (the default settings tab).
3. Enter the server URL and its access token; confirm that you trust this server to receive audio.
4. Click **Choose model folder**. Navigate the server's configured model collection.
5. Click **Load this model locally**. Wait for loading and silent inference warm-up to complete;
   it can take up to two minutes. Warm-up uses a separate stream, never your first recording.
6. The app connects to the entered IP. Wait for **Glyph connected**, then click BOOT to start;
   BOOT or the app's stop control finishes. First words still need model context/CPU time,
   but received audio is queued from the first packet, not skipped until text appears.

**The folder picker browses the server's disk.** It is not the phone's filesystem and
does not upload hundreds of megabytes from a browser. Put/download the model on the
server first. A phone connected to your computer sees that computer's configured folders.
Refresh clears browser tokens/configuration; the backend keeps weights loaded but a
new page must authenticate/select a model again. Disconnect before changing models.

## Phone/LAN and optional Glyph bridge

Example: server computer `192.168.1.10`, board `192.168.1.42`:

If you used the installer, substitute `.tools/web-venv/bin/python` (Windows
`.tools\web-venv\Scripts\python.exe`) for `.venv/bin/python` below. A developer
checkout may instead reuse `.tools/local-stt-venv`. Keep the computer awake.

```sh
.venv/bin/python -m server.app \
  --models-dir /absolute/path/to/my-models \
  --host 0.0.0.0 \
  --origin http://192.168.1.10:8765 \
  --glyph-host 192.168.1.42
```

Open `http://192.168.1.10:8765` on your phone. Permit this port only on your trusted LAN.
`--origin` is the exact browser origin, **without a trailing slash**; repeat it for
additional approved origins. The server validates both Origin and Host and does not
accept wildcard CORS. Backend tokens remain in tab memory, never URL query strings.

The optional `--glyph-host` enables the browser's same-origin WebSocket bridge in
local mode. It allows only that private IPv4 on **8080 `/audio`**, without redirects,
DNS resolution or an arbitrary proxy URL. Repeat the flag for additional permitted
boards, though only one browser/board stream is supported at a time. The server must
be able to reach the board on its LAN. If DHCP changes the board IP, update the flag
and restart, or assign the board a DHCP reservation.

Without `--glyph-host`, the browser connects directly to the Glyph as before: local
HTTP development only, subject to browser local-network policy. The server model
option does not bypass that policy. Bluetooth setup has been removed; all clients
use [manual IP connection and Wi-Fi portal setup](../kit/CONNECTION.md).
Foreground use remains required; leaving/locking the page interrupts the session.

### HTTPS hosting

Put the backend behind your own HTTPS reverse proxy and set `--origin https://your-host`.
Preserve the original Host header and forward WebSocket upgrades for `/api/glyph`.
With `--glyph-host` enabled, the page uses same-origin **HTTPS/WSS**, while only the
backend makes the local plain-WebSocket hop to the board. That solves this deployment's
browser mixed-content boundary without putting a certificate on the C6.

Example Caddy site configuration (supply a domain/certificate trusted by your devices):

```caddyfile
voice.example.com {
    reverse_proxy 127.0.0.1:8765
}
```

The service is intended for **one trusted user**, not public multi-tenant hosting.
For a workshop, run one server per participant/computer; use the
[manual-IP workshop guide](../docs/WORKSHOP.md) rather than sharing one worker/token.
Keep it LAN/private-network-accessible; do not port-forward the development server.
HTTP LAN transport does not encrypt tokens or audio against other network participants.
For untrusted networks use trusted TLS/private-network access. This change does not
provision DNS, certificates, tunnels, firewall rules or a hosted account.

## Optional Docker Compose

Container files are provided; the container build/runtime was **not exercised** in
this environment. The direct Python flow and production web build were tested.
The image downloads software dependencies at build time, **not model weights**.

1. Copy `server/.env.example` to `server/.env`; fill the absolute model directory,
   board IPv4, exact browser origin and a private generated token.
2. Run from the repository root:

```sh
docker compose --env-file server/.env -f server/compose.yaml up --build
```

The models mount is read-only, the container runs as UID 10001 without Linux
capabilities, and its root filesystem is read-only. Ensure that UID can read your
model files. Default published port is loopback only. For a trusted LAN, explicitly
set `GLYPH_BIND_ADDRESS=0.0.0.0` and the matching `GLYPH_ORIGIN`. Docker must have a route
to the board; host firewall and Docker Desktop routing may need operator configuration.

## Text-summary relay

`POST /api/summary` is authenticated by the same local-server token and exact
Host/Origin checks as model operations. It forwards text only to allowlisted
DeepSeek chat-completions or OpenAI Responses HTTPS endpoints; no arbitrary URL,
redirects, audio uploads, provider keys in URLs, or server-side credential storage.
The browser supplies its personal key per request after one-time configuration
consent. Keep this server private/trusted; do not distribute a shared paid key.

The web app supports automatic summaries after clean recording finalization and
manual summaries. Defaults match Android: `deepseek-flash` / `gpt-4.1-mini` (editable
for account-compatible models). The prompt asks for faithful, natural English
paragraphs and treats the transcript as untrusted data. OpenAI uses `store:false`;
DeepSeek disables thinking. These controls do not override provider retention policies.
The OpenAI integration follows the [official Responses request schema](https://developers.openai.com/api/reference/cli/resources/responses/methods/create),
and DeepSeek follows its [chat-completion schema](https://api-docs.deepseek.com/api/create-chat-completion/).

One in-flight summary per server uses a separate lock from speech inference.
Requests are bounded to 64 KiB including JSON/key, with 48 KB UTF-8 source text and
2,048 output tokens. Like Android, timeouts are 15 seconds to connect, 90 seconds
without incoming data and 120 seconds overall (browser allows 130 seconds);
provider response cap: 1 MB. Failed,
incomplete/refused, empty or non-Latin-script outputs are not saved. The script
check does not guarantee English or factual accuracy. No automatic retry.
Browser disconnect cancels the local outbound request and frees its slot, but
cannot retract already-delivered text or guarantee no provider charge. Cancelled
and deleted requests cannot restore results into browser history.

API keys and speech text are not logged or persisted by this service. Summary
history/source snapshots stay in browser IndexedDB. TLS protects the provider hop;
use localhost or trusted HTTPS for the browser-to-server hop too. Provider tests
use mocked responses only, not a real paid account. Summaries need internet;
local recognition and raw history do not.

### If web summaries appear stuck

Stop the launcher with Ctrl+C and start it again after a server-code update;
Python does not hot-reload an already running backend. In the browser disconnect
Glyph, apply **Update app** (or hard refresh), and re-enter tab-only credentials.
Use the same DeepSeek model name as the working Android configuration.

The status box beside Summarize now shows waiting/saving/error states and elapsed
time. A completed response is still displayed for copying if IndexedDB cannot save
it. The server terminal prints `[summary]` records containing only a random request
ID, allowlisted provider name, stage, outcome, HTTP status and elapsed time—not keys,
transcripts or provider bodies. Share the error text and matching request-ID lines
when reporting failures; do not share tokens/keys. No automatic billable retry.
The server computer needs internet separately from the phone. DNS, certificate,
connection, provider HTTP and timeout failures have distinct messages; never solve
certificate failures by disabling HTTPS verification.

## Streaming, resources and privacy

- Browser PCM is batched into **200 ms transport requests**, not whole recordings.
  Parakeet retains encoder-window/decoder state and emits partial text as ready. Its
  1.12-second context preset plus CPU/network time determines latency—not 200 ms alone.
- Finalization adds the same 20 ms silent feature tail as Android and signals
  `input_finished`; the model handles its final right context. No repeated full-recording decoding.
- One model and recording worker per server. Changing models while recording is rejected.
  Audio operations are serialized with exact sequence acknowledgments; failed audio is
  not retried, because retrying could duplicate words.
- A separate native process isolates model crashes. Model loading has a 120-second
  deadline; individual inference operations have a 30-second deadline. Failures terminate
  the worker and require model reload. This does not protect the host against system-wide OOM.
  Pipe send/receive calls run on an I/O thread, not on the HTTP/bridge event loop.
  A readable pipe can still contain an incomplete reply, so waiting for the full
  message is also offloaded and deadline-bounded. This prevents worker IPC stalls
  from directly stopping socket service; it cannot fix router/OS/Wi-Fi stalls.
- No fixed recording-duration cutoff. There are bounded PCM queues/request sizes and
  a 1 MB transcript response limit. Excess backlog or resource limits interrupt clearly.
- The board WebSocket bridge explicitly disables total-session and receive deadlines.
  Its five-second connection deadline and heartbeats only detect connection problems;
  they are not a recording timer. The browser warns on a short PCM pause without
  closing an otherwise connected session. BOOT/app STOP still waits for the board's end.
- **60 seconds is an idle cleanup timeout, not a recording limit.** Active audio keeps
  renewing the session; abandoned browser sessions are released. Cancellation waits for
  the outstanding inference operation before discarding state. No idle inference loop.
- Audio is transient RAM only. The service writes neither recordings nor transcripts.
  Model folders are read-only to the application. Browser history remains IndexedDB;
  clearing it does not involve server storage. Language/OS buffers prevent guaranteed secure erasure.
- Token grants model browsing/loading, local inference/bridge and summary-relay access to one trusted
  operator. This is not an account system or a public API service. HTTP access logs are disabled;
  avoid adding reverse-proxy logs that capture request bodies or Authorization headers.
- Install software and obtain models once; thereafter recognition needs no internet
  while browser, backend and board can still communicate locally. This is **not phone-only**.

## Checks

IPC regression tests block worker sends and partial replies while checking that
the event loop remains responsive, with deadline/cancellation cleanup. Firmware
r10's queue-budget test covers the reported 4104 ms stall plus 600 ms of prior
backlog. Neither substitutes for measuring actual radio delivery and free heap.

```sh
.venv/bin/python -m unittest server.test_server server.test_summary -v
cd web
npm test
npm run build
npm run test:e2e
```

Optional real-time bridge soak, from the repository root (use the launcher venv,
or your activated server environment):

```sh
GLYPH_LONG_STREAM_SECONDS=420 .tools/local-stt-venv/bin/python -m unittest server.test_long_bridge -v
```

This sends synthetic PCM through real loopback WebSockets and the actual bridge
for seven minutes, checks that no early stop occurs, then verifies explicit STOP
and the exact end byte count. It does not measure Wi-Fi reliability or Parakeet
inference speed. Leave the environment variable unset to skip the soak in normal tests.

Optional real engine test (a mono PCM16 speech WAV, not silence):

```sh
GLYPH_TEST_MODEL=/path/to/parakeet-folder GLYPH_TEST_WAV=/path/to/speech.wav \
  .venv/bin/python -m unittest server.test_server.RealInferenceTest -v
```

Optionally set `GLYPH_TEST_PREFIX` to the fixture's known opening words to assert
that the start is present (case and punctuation ignored). Loading now includes
a disposable silent inference warm-up before the API advertises readiness.

Set `GLYPH_TEST_HTTP=1` as well to include real authenticated loopback HTTP inference.
That additional end-to-end HTTP/model test also passed here. Without the flag it is
skipped so the pure inference test can run under a network-restricted sandbox.

Verified here: real Parakeet partial/final transcription of the existing speech fixture
and silence, with network access restricted; 12 backend protocol/security/bridge tests;
68 web unit and 16 Chromium desktop/mobile-layout tests. The low-amplitude digits fixture
returned empty text in a separate probe: these are integration checks, not an accuracy
benchmark. Physical Glyph audio, real iPhone Safari, browser pairing, sustained performance,
Docker and a deployed TLS reverse proxy still require testing. No board firmware or
Android inference code was changed for this local-server feature.

Runtime API source: [sherpa-onnx streaming Python interface](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/python/sherpa_onnx/online_recognizer.py).
