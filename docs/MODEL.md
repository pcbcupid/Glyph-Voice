# Parakeet Unified English streaming model

The user-selected model is **nvidia/parakeet-unified-en-0.6b**, not Parakeet TDT v3.
The APK includes sherpa-onnx's **INT8 streaming 1120ms** export, not its similarly
named **non-streaming** export. Runtime is sherpa-onnx **1.13.8** on the phone CPU.

## What “live” means here

BOOT press opens one native `OnlineStream`. Every incoming PCM packet is decoded
to floats and fed immediately through that stream's persistent band-limited
resampler/feature extractor. When a model chunk is ready, decoding updates the RNNT
state and the UI shows the current text. The second BOOT click calls `inputFinished()` and
decodes the final short chunk with padded right context before presenting final text.
The same stream is retained between the two clicks, including silence.

This is the model's **stateful buffered streaming** algorithm: a sliding left/center/
right-context window, with decoder state carried forward. It recomputes left encoder
context; it is not cache-aware encoder streaming and is not repeated offline decoding
of a whole growing recording. The runtime also supplies the model-specific feature
normalization and attention behavior from the streaming graph.

The chosen preset has 5.6 seconds left context, 0.56 seconds center and 0.56 seconds
right context. **1.12 seconds is model context latency, not a phone latency promise.**
CPU processing, packet arrival and scheduling add delay. Once underway, partial text
can change around each model chunk. Extremely short utterances may produce only a
final result. Partials can be revised; on interruption, unfinished audio is discarded
but the latest visible words are retained and checkpointed locally as Interrupted.

Sources:

- [NVIDIA model and streaming configurations](https://huggingface.co/nvidia/parakeet-unified-en-0.6b)
- [Pinned streaming exporter](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/scripts/nemo/parakeet-unified-en-0.6b/run-streaming.sh)
- [Pinned native streaming implementation and final-chunk handling](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/online-recognizer-transducer-nemo-parakeet-unified-impl.h)

## Limits, privacy and lifecycle

- 64-bit Android (ARM64 phones, x86_64 emulator), API26+. Intended validation target:
  4–6 GB RAM; the user's phone has 12 GB. Neither speed nor peak memory is measured yet.
- Up to four CPU inference threads, one serialized native session. Native kernels
  cannot be interrupted immediately; cancellation drops results and safely releases
  the stream once the current kernel returns. No parallel model free/decode.
- Queue capped at 30 seconds of PCM and 4096 packets. Temporary scheduling stalls
  can catch up; sustained overload stops intake, drains accepted audio and marks
  the result interrupted instead of growing memory or silently dropping words.
- No configured button-session duration limit. In local mode there is no speech upload or recorded audio files,
  phone microphone, STT server, or automatic online STT fallback. Optional cloud mode is documented in [Kit app setup](../kit/APP.md). Text history is local; the
  separate optional Summarize action uploads selected text with consent. Audio chunks are discarded
  after consumption. Native feature/decoder state is released at segment completion.
- Current firmware sends 16 kHz PCM, filtered/downsampled from 48 kHz on the MCU.
  The phone does not need to resample it. Older 48 kHz firmware input is still
  supported and resampled *statefully*, not independently per 20 ms packet.
  A 20 ms zero tail flushes resampler/features at the stop click; the native Unified runtime
  handles missing right context. The 20 ms is not included in the wire byte count.
- Continuous model work occurs only during a button session/finalization. No idle
  inference. Model weights stay loaded while the controller lives.
- English, punctuation and capitalization. No guarantee of error-free transcription
  or universal best accuracy. INT8 quality and real microphone performance need checks.

For long sessions, the pinned native implementation reads a rolling feature window
and discards features older than that window: see the [Unified decoder window](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/online-recognizer-transducer-nemo-parakeet-unified-impl.h)
and [feature extraction / GetFrames / PopWrapper](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/features.cc).
This is source inspection, not a phone memory benchmark. Decoder tokens/timestamps
and saved/displayed text still grow with speech. The Java wrapper retrieves the full
result only after a decode, not for every incoming packet. There is no artificial
session timer; available resources and native implementation limits still apply.
The old `AudioBuffer` retains a bounded 60-second **legacy batch-test** buffer; the
production streaming path never accumulates recordings there. The development WAV
receiver streams chunks; the host Python simulator loads its selected WAV into RAM.

## Bundling and exact provenance

Four inference files total **663,048,980 bytes**. This is storage, not peak RAM.
The APK bundles a deterministic stored ZIP, automatically installed from local
assets on the first Connect action with progress and per-file checksum validation. There is no
phone download. Allow at least **2 GB free storage** before installation and more
for APK updates. The large asset is excluded from Git; `tools/fetch_assets.py` reconstructs it from pinned upstream files.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Upstream streaming `.tar.bz2` | 501356335 | `4788229a6dd03be33f8243ccee48e33a8d15df7b448cb99150b0ccddd1b02d74` |
| Bundled streaming `.zip` | 663049938 | `ab5d28779f17ec0ce60ec537adc33f7fd5730d0adc68db0ab58ad5596b84eb4e` |
| sherpa-onnx 1.13.8 AAR | 50129134 | `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` |

Included in release APKs and prepared developer checkouts, not Git. To reproduce on a computer without these assets
(build preparation only, Python 3.11+, internet needed):

```sh
mkdir -p .tools/parakeet-download app/libs
curl -fL --retry 3 -o .tools/parakeet-download/parakeet-unified-streaming-1120ms.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-1120ms.tar.bz2
python3 tools/package_parakeet.py .tools/parakeet-download/parakeet-unified-streaming-1120ms.tar.bz2
curl -fL --retry 3 -o app/libs/sherpa-onnx-1.13.8.aar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
```

The packaging script verifies the upstream checksum and copies only encoder,
decoder, joiner and tokens unchanged, excluding recordings. Individual hashes/sizes
are pinned in `LocalModelManager`. No arbitrary model substitution/fallback is used.
Old Vosk and TDT bundles are preserved outside the APK under `.tools/legacy-model/`
on the development computer. Old private model directories on upgraded phones are
unused; clearing app storage removes them and prepares Unified again offline.

## License

The original model now names the **NVIDIA Open Model License**, not CC BY 4.0.
The APK includes the required NVIDIA attribution, source/conversion notices and a
copy of the NVIDIA license PDF under `assets/licenses/`. Runtime licenses are
Apache-2.0 (sherpa-onnx) and MIT (ONNX Runtime). The privacy footer shows attribution.
Review these notices before redistribution.

## Verification

Model/archive integrity and build checks are distinct from runtime validation.
Phone/Glyph tests remain deferred at the user's request. Verify live words before
release, final tail words, long speech, pauses, silence/noise, reconnect/background
cancellation, peak RSS, thermal throttling, and no-WAN operation on 4/6/12 GB phones.
The model's published context latency is not evidence of phone real-time performance.
