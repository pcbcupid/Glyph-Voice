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

1. Open the right drawer → **Speech recognition**, or use **Connect Glyph → Enter an existing Glyph IP**.
2. Select **Local model** (the default settings tab).
3. Enter the server URL and its access token; confirm that you trust this server to receive audio.
4. Click **Choose model folder**. Navigate the server's configured model collection.
5. Click **Load this model locally**. Wait for loading to complete; it can take up to two minutes.
6. Enter/connect your Glyph's IP. Click BOOT to start; BOOT or the app's stop control finishes.

**The folder picker browses the server's disk.** It is not the phone's filesystem and
does not upload hundreds of megabytes from a browser. Put/download the model on the
server first. A phone connected to your computer sees that computer's configured folders.
Refresh clears browser tokens/configuration; the backend keeps weights loaded but a
new page must authenticate/select a model again. Disconnect before changing models.

## Phone/LAN and optional Glyph bridge

Example: server computer `192.168.1.10`, board `192.168.1.42`:

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
option does not bypass that policy. Bluetooth setup still needs a supported secure
browser context; LAN HTTP and iPhone Safari retain [Wi-Fi setup fallback](../docs/BLUETOOTH_SETUP.md).
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
- No fixed recording-duration cutoff. There are bounded PCM queues/request sizes and
  a 1 MB transcript response limit. Excess backlog or resource limits interrupt clearly.
- **60 seconds is an idle cleanup timeout, not a recording limit.** Active audio keeps
  renewing the session; abandoned browser sessions are released. Cancellation waits for
  the outstanding inference operation before discarding state. No idle inference loop.
- Audio is transient RAM only. The service writes neither recordings nor transcripts.
  Model folders are read-only to the application. Browser history remains IndexedDB;
  clearing it does not involve server storage. Language/OS buffers prevent guaranteed secure erasure.
- Token grants model browsing/loading and local inference/bridge access to one trusted
  operator. This is not an account system or a public API service. HTTP access logs are disabled;
  avoid adding reverse-proxy logs that capture request bodies or Authorization headers.
- Install software and obtain models once; thereafter recognition needs no internet
  while browser, backend and board can still communicate locally. This is **not phone-only**.

## Checks

```sh
.venv/bin/python -m unittest server.test_server -v
cd web
npm test
npm run build
npm run test:e2e
```

Optional real engine test (a mono PCM16 speech WAV, not silence):

```sh
GLYPH_TEST_MODEL=/path/to/parakeet-folder GLYPH_TEST_WAV=/path/to/speech.wav \
  .venv/bin/python -m unittest server.test_server.RealInferenceTest -v
```

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
