# Web migration decision — 2026-09-30

## Decision

Start **React + TypeScript + Vite**, progressively installable as a PWA. The user
explicitly accepted **foreground recording with the screen unlocked**. Preserve
the Java Android app and C6 firmware as the working baseline until web acceptance
checks pass. No deployment, provider account, paid service or firmware change is
authorized by this document itself.

React is the UI library, not a replacement for networking, inference or operating
system services. A PWA is a web app with installation/offline-shell features, not
a native app that inherits Android service permissions.

| Concern | React web/PWA | React Native + Expo |
| --- | --- | --- |
| Distribution | Browser URL; optional home-screen install | Native Android/iOS packages; web target also possible |
| UI | HTML/CSS/DOM, responsive web layout | Native components; React Native Web can share some UI |
| Need to own a Mac | No | No for EAS cloud iOS builds; macOS build tooling runs remotely |
| Apple requirements | No app signing for a website | Signing/provisioning and Apple account requirements apply |
| Existing Java/native STT | Must port/build browser runtime | Can bridge native engines, with separate iOS integration |
| Locked/background recording | Cannot promise continuous execution | More native APIs, but iOS background modes still constrain feasibility |
| UDP board discovery | Not exposed to ordinary web pages | Native networking module possible |
| Existing board ws:// | Browser mixed-content/local-network constraints | Native platform networking policies must still be handled |
| Best fit now | Fast link-based access across devices; accepted foreground limitation | Revisit if native inference/background behavior becomes mandatory |

Not owning a Mac alone is **not** a reason to rule out React Native. Expo EAS builds
can compile iOS remotely. It does not remove signing, real-device testing, native
module work or iOS background restrictions. Expo Go is not a drop-in host for the
existing Java Parakeet AAR.

## Milestones and gates

Update: [self-hosted local inference](../server/README.md) is implemented with a
server-folder picker and optional same-origin allowlisted Glyph bridge. This is a
separate execution option, not completion of the phone-only browser inference milestone.
Real deployment/phone validation and summary parity remain outstanding.

1. **Portable foreground slice (started):** React shell, TypeScript protocol/session,
   local raw history, manual development connection, explicit optional cloud speech,
   cancellation/reconnect and software tests. See [web README](../web/README.md).
2. **Production transport:** choose and physically validate secure reachability
   from Android Chrome, iPhone Safari and desktop. Existing ws:// plus UDP cannot
   simply be placed behind an HTTPS React URL.
3. **Browser on-device inference:** evaluate sherpa-onnx WASM and the exact Parakeet
   Unified graph/runtime compatibility. Move inference to a worker, measure memory,
   streaming latency, interruption and model installation on real phones. WebGPU
   availability alone does not prove model/operator support or acceptable performance.
   Do not promise the Android 0.6B model runs until proven. Do not silently substitute
   browser speech APIs or cloud inference for the selected local mode.
4. **Summary parity:** port the natural English prompt, provider/credential boundary,
   one-completion/one-summary semantics, original/result switch, cancellation,
   deduplication and deletion protection. Decide direct provider CORS versus an
   explicitly approved relay before implementing production credentials. No paid
   production key in client code, Vite env variables, or source control.
5. **Migration and acceptance:** Android text export/import if required; real
   iPhone/Android/desktop tests; new-session clearing, stop acknowledgment, offline
   local recognition, provider failures, storage exhaustion, refresh and background
   interruption. Keep Android available until these are actually verified.

## Production transport choices still requiring a decision

- **Device-served LAN app:** could serve the web UI from the Glyph on HTTP and use
  same-LAN ws://. Keeps data local, but is not an HTTPS installable/offline-capable
  PWA environment and restricts browser APIs. Needs firmware/flash-size validation.
- **Trusted local TLS endpoint/bridge (self-hosted option implemented):** the Python
  server serves the app and bridges an operator-allowlisted Glyph. HTTPS + WSS can retain local transport,
  but certificate provisioning and reachability are real product work. A laptop
  bridge introduces an extra required device and is not a phone-only solution.
- **Remote relay with an outbound board connection:** can make hosted HTTPS clients
  practical, but introduces internet/backend infrastructure and audio transit outside
  the LAN. Requires explicit approval and revised privacy/security design. Not built.

The preview uses private IPv4 on HTTP **only for development**. The subsequent
[Bluetooth setup update](BLUETOOTH_SETUP.md) can discover that IP and provision Wi-Fi
in supported secure browsers (localhost is useful for development). It does not solve
the HTTPS-to-ws:// audio boundary by itself; the new server bridge handles that hop.
Safari's lack of Web Bluetooth still requires its fallback. No claim
of production iPhone hotspot compatibility is made.

## Boundaries worth retaining

```text
AudioReceiver → validated PCM/session → SpeechRecognizer → transcript/history
                                                       → future SummaryClient
React UI observes state; it does not parse packets or own inference.
```

Preserve protocol version 1, start/audio/end byte-count validation and STOP
acknowledgment. Never treat transport loss as successful completion. Preserve
partial text; only the next accepted start clears the active transcript. A duration
limit is not needed, but a bounded backlog and provider/input limits still are.

Browser HistoryStore is IndexedDB rather than Android SQLite. Browser storage is
origin-specific and can be evicted. API keys cannot be imported from Android
Keystore and the web must not claim equivalent key protection.

## Sources checked for this decision

- [React: building from scratch with tools such as Vite](https://react.dev/learn/build-a-react-app-from-scratch)
- [React Native components](https://reactnative.dev/docs/intro-react-native-components)
- [Expo EAS iOS cloud build and signing](https://docs.expo.dev/tutorial/eas/ios-development-build-for-devices/)
- [WebKit: inactive iOS tabs may be suspended](https://webkit.org/blog/8970/how-web-content-can-affect-power-usage/)
- [Chrome local-network access and evolving transport restrictions](https://developer.chrome.com/blog/local-network-access)
- [Screen wake lock visibility/security limitations](https://developer.mozilla.org/en-US/docs/Web/API/Screen_Wake_Lock_API)
- [WebGPU in Safari 26](https://webkit.org/blog/17333/webkit-features-in-safari-26-0/)
- [sherpa-onnx WASM builds](https://k2-fsa.github.io/sherpa/onnx/wasm/build.html)
