# Connection and troubleshooting

Source-built **0.12.0 / transport-r7** adds **Connect Glyph → Bluetooth setup**,
which discovers the board and sends Wi-Fi details with a private pairing PIN.
See [Bluetooth setup](../docs/BLUETOOTH_SETUP.md). The hotspot UDP flow below remains
available through **Find via Wi-Fi**; Bluetooth also works for address discovery
on a shared router, provided the router permits client-to-client traffic.

## Automatic hotspot discovery

The Glyph joins the hotspot saved through its first-use setup page. Once connected,
it sends a small UDP announcement every second to the DHCP gateway — the phone
hosting that hotspot — on port **40123**. The app reads the sender's private IPv4
address, validates the service/version/board ID, then connects to its WebSocket on
port **8080**, path `/audio`. No subnet scan, Android hotspot-client-list access,
location permission, or manually entered board IP is required.

The first search gathers boards for three seconds. One board connects automatically;
several boards show a **Choose Glyph** action. The chosen stable board ID is saved.
Announcements update its address after DHCP changes. The existing transport also
retries dropped connections. Interrupted audio is not resumed as a successful recording.
To select a different board, disconnect, then use **Choose another board** in the
Connect dialog. If the saved board is absent but another is found, Choose Glyph
is offered directly.

This discovery flow is designed for the **phone hosting the hotspot**. Connecting
both devices to an unrelated router sends board announcements to that router;
it is not a supported automatic-discovery topology. Announcements and audio sockets
are local and unauthenticated: use your own trusted, password-protected hotspot.

## If the board is not found

- Use matching app **0.11.0+** and firmware **transport-r6+**. Older firmware does not announce.
- Check power, exact hotspot name/password, and 2.4 GHz / WPA2 compatibility mode.
- Enable the hotspot on the same phone running GLYPH VOICE.
- Finish first-use setup; the board must leave `GLYPH-Setup-xxxxxx` and join the hotspot.
- Disable hotspot automatic shutoff and check client limits.
- Temporarily disable VPN routing that prevents access to local devices.
- Allow local-network access if your Android version asks. OEM firewall/tethering
  behavior still needs device-specific testing; reconnect after changing settings.
- Turn off the hotspot and hold BOOT for five seconds after normal startup to
  reconfigure incorrect credentials.

USB serial at 115200 baud can show diagnostic connection information, but is not
needed for normal setup or discovering the board's IP.

## Connected but no words

Wait until the local model or cloud recognizer is ready, then start with BOOT.
Check microphone pin labels and signal format against [Hardware](HARDWARE.md).
Local English recognition needs enough free RAM and storage. Cloud mode needs a
compatible transcription URL, model, credentials and working internet on the phone.
Very short speech may only produce a final result after stopping.

## Screen off or network interruption

Keep the foreground notification active and follow the app's battery prompts.
Disable hotspot auto-off, Battery Saver and Low Power Standby while recording if
those settings interrupt tethering. Some phones require unrestricted background
activity or auto-start. Force-stop stops the app. Saved text remains in history.

For a real board/phone verification sequence, use [Testing](../docs/TESTING.md).
