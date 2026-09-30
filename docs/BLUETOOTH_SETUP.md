# Bluetooth setup — 0.12.0 / transport-r7

This working-tree update adds native Android BLE discovery and optional Web Bluetooth
provisioning. Install **both the new app and firmware**. Existing transport-r6 audio
and UDP discovery remain compatible, but old firmware has no Bluetooth setup service.
No firmware has been flashed or release published by this change.

Subsequent web update: the [self-hosted model server](../server/README.md) now supplies
an optional same-origin HTTPS/WSS Glyph bridge and server-local Parakeet. References
below to missing browser transport/inference describe the standalone browser path;
phone-only browser inference is still not implemented.

Local build outputs: `app/build/outputs/apk/debug/app-debug.apk` and
`.tools/firmware-ble/glyph_voice.ino.bin` (application only), with a merged image beside it.
The APK is **debug-signed**: it cannot upgrade a differently signed release install.
Build/sign with the existing release key for an in-place upgrade; do not uninstall
your working app just to bypass a signature error, as that removes its local history.
With the same existing partition layout, flashing only the application at `0x10000`
preserves NVS. A full merged image at `0x0` may overwrite saved Wi-Fi, PIN and bonds;
use the flashing guide and expect re-pairing after such a reset.

## What works where

| Client | Discovery/setup | Audio afterward |
| --- | --- | --- |
| Android Java app | Actual nearby compatible Glyph results on an animated radar; system pairing PIN dialog | Direct local Wi-Fi; existing on-device Parakeet and background service unchanged |
| Supported Chrome/Edge browser, HTTPS or localhost | Tap Find nearby; browser-owned chooser; selected Glyph appears on radar | Localhost development HTTP can use existing ws://; hosted HTTPS still requires a secure audio transport implementation |
| iPhone Safari, Firefox, or phone opening LAN HTTP | No Web Bluetooth; local Wi-Fi setup page and manual IP fallback | Existing web-preview limitations still apply; provisioning does not make local Parakeet or production iPhone audio available |

Browsers deliberately do not expose silent nearby-device enumeration. The React radar
shows the **selected** board, not an invented device scan. Android shows real scan
results; radar positions are decorative, not physical direction or distance estimates.
Only compatible setup-service advertisements are considered, not every nearby headset.

## Android: first setup

1. Build/flash transport-r7 using [firmware instructions](../firmware/README.md).
   Open USB serial at **115200**, reset the board, and record its private six-digit **Pairing PIN**.
   It is generated once and kept across normal reboots. For distributed kits, put it
   on a private label; never publish PINs or use a shared default.
2. Enable your phone's **2.4 GHz / WPA2 hotspot**, or join a shared 2.4 GHz network
   that allows phone-to-board traffic. Have its exact name and password ready.
3. Install the new APK. Choose **Connect Glyph → Bluetooth setup → Scan nearby**.
4. Allow Nearby devices on Android 12+, or Location permission and system Location
   on Android 8–11 (required by those OS versions for BLE scanning; no location is collected).
   Enable Bluetooth when asked. Select your **GLYPH-xxxxxx** result.
5. Enter this board's PIN in the Android pairing prompt. Enter Wi-Fi details in the
   setup screen, then **Send securely & connect**. Neither Android nor a web page
   can automatically retrieve your saved hotspot password.
6. Glyph joins Wi-Fi and returns its address. Android hands it directly to the existing
   voice connection. If already joined, it skips credential entry. Wait for speech
   recognition readiness, then click BOOT to start/stop as before.

The BLE-discovered address is a direct-IP connection, not continuing UDP discovery.
If DHCP later changes it, reopen Bluetooth setup to read the new address, or use
**Find via Wi-Fi** on the phone hosting the hotspot for the existing UDP tracking flow.
An unrelated router does not forward those gateway-directed discovery announcements
to your phone; BLE can still provide the address if router client isolation is disabled.

## React

Run the [web preview](../web/README.md). Choose **Connect Glyph → Find nearby Glyph**.
On a supported secure browser origin, select the board in the browser picker, confirm
its PIN if the OS requests pairing, and submit Wi-Fi details. Keep the page visible.
**Continue to voice** uses the discovered address; it never opts into cloud speech.
The preview still asks for speech configuration because local browser Parakeet is
not implemented. Android local Parakeet is untouched.

**Do not confuse Bluetooth setup with a complete HTTPS audio solution.** Web Bluetooth
requires a secure context, while the existing board serves plain `ws://`. The app
continues to reject that mixed-content connection from HTTPS. This update does not
deploy a relay, TLS bridge, board certificate, or cloud backend. Desktop localhost
development is the common context that supports BLE setup and existing plain audio.

Safari/iPhone fallback: choose **iPhone / Wi-Fi setup fallback**, join the board's
`GLYPH-Setup-xxxxxx` AP (password `glyphvoice`), and open `http://192.168.4.1`.
Enter your target network, then leave the setup AP and enable/rejoin that network.
The board has 30 seconds to join. The fallback uses HTTP and a shared AP password;
it does **not** have the private-PIN protections of BLE. Use it only in a trusted
environment. It stays available after the BLE advertising window expires on an
unconfigured board, until a connection attempt closes it.

## Lifecycle, privacy and failure handling

- BLE opens for **five minutes after boot**; power-cycle to reopen. Holding BOOT for
  five seconds while **no audio client is connected** reboots into setup, even if
  Wi-Fi is connected. Short clicks retain recording behavior.
- BLE is setup-only, one peer at a time. It closes and releases host/controller
  resources when an audio WebSocket connects. Scanning does not run in the background.
- Standard BLE bonding/passkey authentication and encrypted/authenticated GATT
  access are required. Firmware requests LE Secure Connections with MITM and rejects
  authenticated sessions lacking encryption or a 16-byte key. The operating system
  handles pairing; there is no custom browser cryptography or plaintext BLE fallback.
- Wi-Fi passwords are not written to app/browser storage, URLs, analytics or logs.
  Input is cleared after sending/leaving the screen. Temporary byte buffers are
  cleared where possible; Java/JS strings and OS stacks prevent a secure-erasure guarantee.
- The board persists one atomic credential record **only after joining successfully**.
  Old separate NVS keys are migrated after success. Failed joins preserve the previous
  saved record for next reboot; retries use the new candidate until then. Flash/NVS
  encryption is not configured by this prototype; physical flash access is a separate threat.
- A timeout/disconnect is **not success**. Reconnect to check whether the board joined.
  Wrong-password failure offers retry; old saved settings are not destroyed. Forget a
  stale OS Bluetooth bond if you erased/replaced the board's NVS or PIN.
- Only 1–32 UTF-8-byte SSIDs and 8–63 printable-ASCII passwords are supported. Open,
  enterprise/EAP networks and 64-hex-digit raw PSKs are outside this setup contract.
- This secures BLE credential transfer, **not** the existing unauthenticated LAN
  audio socket, UDP announcements, or SoftAP fallback. Use a trusted private network.

## Shared GATT contract

Name: `GLYPH-<last six station MAC hex digits>`. The full 12-hex station MAC is the
stable ID; the OS BLE address is not assumed to equal the Wi-Fi address.

| Characteristic | UUID | Access |
| --- | --- | --- |
| Setup service | `c8c0f100-7d8c-4b9e-9a26-12f467a3e001` | Advertised service filter |
| Status | `c8c0f101-7d8c-4b9e-9a26-12f467a3e001` | Authenticated/encrypted READ, at most 224 bytes |
| Command | `c8c0f102-7d8c-4b9e-9a26-12f467a3e001` | Authenticated/encrypted WRITE WITH RESPONSE, at most 20 bytes |

Status is UTF-8 JSON, e.g.:

```json
{"version":1,"id":"AABBCCDDEEFF","state":"connected","ip":"192.168.4.2","port":8080,"path":"/audio","error":""}
```

States: `ready`, `queued`, `joining`, `connected`, `failed`. Failure codes: `packet`,
`network`, `storage`. Clients validate version, board ID, port/path and private IPv4;
they do not trust arbitrary URLs or display raw remote error bodies. Poll while joining.
GATT long reads assemble the status when it exceeds the default ATT MTU.

Binary writes (all numbers one byte):

1. BEGIN: `[1, totalBodyBytes]`, total 11–97.
2. DATA: `[2, offset, payload…]`, up to 18 payload bytes; exact sequential offsets.
3. COMMIT: `[3]`, only valid after exactly the declared byte count.
4. CANCEL: `[4]`, clears an **uncommitted** frame; not a rollback after COMMIT.

Body: `[ssidByteLength, passwordByteLength, ssidUTF8…, passwordASCII…]`.
No NUL terminators on the wire; meaningful spaces are preserved. Invalid/truncated,
oversized, reordered or duplicate DATA frames reset the accumulator. One committed
transaction runs at a time; extra writes while joining are ignored. Pairing credentials
are not transported inside this body. No Wi-Fi password can be read back over GATT.

Implementations: `ProvisioningFrame.h` / `BleWifiSetup.h`, Java
`BleProvisioningProtocol` / `BleWifiProvisioner`, TS `BleProvisioner`.

## Verification and hardware checklist

Software checks on 2026-09-30: Android app/instrumentation APK built; lint passed;
69 Java tests passed (3 model/fixture tests skipped); C6 compilation passed; all three
firmware host checks passed; React production build and 63 unit / 14 Chromium browser
tests passed. BLE/browser test devices are mocks, not real radios. iPhone viewport
tests are Chromium, not Safari. No board flashed or phone installed during these checks.

Before distribution, test on actual hardware:

- New board, correct PIN/network → native connection → offline local Parakeet audio.
- Wrong PIN, cancelled chooser/pairing, permission denial/revocation, disabled Bluetooth.
- Wrong password, Unicode/space-containing SSID, 32/63-byte limits, lost hotspot.
- Disconnect/reload/background during each setup step; power loss before/after commit.
- Two phones attempting setup, multiple nearby Glyphs, stale bonds, expired five-minute window.
- Already-configured board and DHCP address changes; old UDP and SoftAP paths.
- Sustained recording after BLE shutdown; inspect heap and audio overrun counters.
- Supported real Android Chrome/desktop BLE stack and iPhone Safari fallback; do not
  treat mock success as OS pairing compatibility or audio reachability verification.

Platform references: [Chrome Web Bluetooth](https://developer.chrome.com/docs/capabilities/bluetooth),
[Web Bluetooth browser compatibility](https://developer.mozilla.org/en-US/docs/Web/API/Web_Bluetooth_API),
[Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
