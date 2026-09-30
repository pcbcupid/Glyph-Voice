# Third-party components

The APK bundles the Parakeet Unified English INT8 streaming model and sherpa-onnx
runtime. Original Vosk code/model are retained only for legacy host checks, not in
the app. Sample speech is not embedded in the main application.

| Component | Version/model | License/source |
| --- | --- | --- |
| Parakeet Unified English | 0.6B, INT8 streaming 1120ms export | [NVIDIA Open Model License / model card](https://huggingface.co/nvidia/parakeet-unified-en-0.6b) |
| sherpa-onnx | 1.13.8 | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx) |
| ONNX Runtime | Bundled by sherpa-onnx | [MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) |
| Kotlin standard library | 2.2.0 | [Apache-2.0](https://github.com/JetBrains/kotlin) |
| Vosk desktop, host tests only | 0.3.45 | [Apache-2.0](https://github.com/alphacep/vosk-api) |
| Legacy host-test speech model | vosk-model-small-en-us-0.15 | [Apache-2.0 per publisher](https://alphacephei.com/vosk/models) |
| JNA, legacy host tests only | See core/build.gradle | [Apache-2.0 or LGPL-2.1-or-later](https://github.com/java-native-access/jna/blob/master/LICENSE) |
| OkHttp / Okio | 4.12.0 / transitive | [Apache-2.0](https://github.com/square/okhttp) |
| AndroidX libraries | See app/build.gradle | [Apache-2.0](https://android.googlesource.com/platform/frameworks/support/) |
| JUnit | 4.13.2, tests only | [EPL-1.0](https://github.com/junit-team/junit4) |
| JSON-java | 20240303, host compilation/tests only | [Public domain](https://github.com/stleary/JSON-java) |
| Python websockets | 15.0.1, development simulator only | [BSD-3-Clause](https://github.com/python-websockets/websockets) |
| Gradle wrapper | 8.13 | [Apache-2.0](https://github.com/gradle/gradle) |
| Arduino ESP32 core, firmware only | 3.3.10 | [LGPL-2.1 and bundled component notices](https://github.com/espressif/arduino-esp32) |
| WebSockets by Markus Sattler, firmware only | 2.7.2 | [LGPL-2.1-or-later](https://github.com/Links2004/arduinoWebSockets) |

**Licensed by NVIDIA Corporation under the NVIDIA Open Model License.** The model
creator is NVIDIA; ONNX INT8 conversion is from sherpa-onnx. Four inference files
are bundled unchanged, repackaged without test recordings. No endorsement is implied.
The model license PDF, attribution, sherpa-onnx license, ONNX Runtime license and
Apache-2.0 text are included in `app/src/main/assets/licenses/`. See
[MODEL.md](docs/MODEL.md) for exact source artifacts and hashes. No Whisper runtime,
OpenAI SDK, or cloud summarization dependency is used. Preserve upstream runtime
notices when redistributing binaries, including transitive components in the AAR.

Optional test fixtures come from the upstream
[Vosk example](https://github.com/alphacep/vosk-api/tree/master/python/example) and
[whisper.cpp JFK sample](https://github.com/ggml-org/whisper.cpp/tree/master/samples).
They are downloaded separately and are not part of the application APK. A fixture
build embeds the speech WAV only in the instrumentation APK, using Parakeet to decode it.

Firmware dependencies remain in the installed Arduino packages/libraries rather than
being vendored here. Preserve their license notices and applicable source/relinking
materials when distributing firmware binaries; see each upstream package's terms.
