# Glyph audio protocol, version 1

This project defines the contract implemented by the app and the included
[Glyph C6 firmware](../firmware/README.md). It is not a claim about stock or other
PCB Cupid firmware. Replace `WebSocketAudioReceiver` if an existing contract differs.

Phone: WebSocket client. Glyph: WebSocket server at `ws://<local-ip>:8080/audio`.
Current transport-r11 uses manual IP entry; no Bluetooth or UDP discovery is active.
Personal hotspot naming and GPIO14 LED status do not change this audio protocol.
The audio socket has no subprotocol or application authentication. The board never
connects to a cloud model or the phone microphone. Remote stop is an optional,
explicitly advertised extension (transport-r5+ / app0.10.0+).
The peer must respond to standard WebSocket ping frames with pong frames.

## First button click (start)

Send one UTF-8 WebSocket text message, at most 2048 characters:

```json
{"type":"start","version":1,"id":"r1","sampleRate":16000,"channels":1,"encoding":"pcm_s16le","control":"stop-v1"}
```

All fields except `control` are required. The recording ID is 1–64 ASCII letters, digits, `_`, or `-`.
Use a fresh ID per press. Rates accepted: 8000, 16000, 32000, 44100, 48000. Channel
count must be 1. Samples must be signed 16-bit two's-complement, little-endian.
No WAV/RIFF headers, sequence headers, compression, or base64 encoding.

The current source firmware uses `sampleRate:16000`, with 1280-byte / 40 ms
audio messages (a shorter aligned final tail is allowed). It captures 48 kHz I2S
and low-pass filters/downsamples to 16 kHz before sending; metadata describes the
actual transmitted PCM, not the raw I2S clock. Older r2/r3 firmware sent 48 kHz PCM.
Its endpoint is `ws://<DHCP-assigned-IP>:8080/audio` on the phone's configured hotspot.
The 16 kHz examples here also apply to the development simulator; rates are declared,
not guessed. One second's end byte count is 32000 at 16 kHz (96000 at 48 kHz).

## While recording (button need not be held)

Send binary WebSocket **messages**, each containing 2–16384 bytes and an even number
of bytes. The current firmware sends 1280 bytes = 40 ms at 16 kHz; older 640-byte /
20 ms messages remain valid without an app change. Send audio continuously,
including silence. WebSocket fragmentation is allowed, but each assembled message
must satisfy this limit. Sample rate and format cannot change during a recording.

There is no configured recording-duration limit or start-to-end deadline. Android
and web show a **Waiting for Glyph audio** warning after 5 seconds without PCM,
but keep the socket, recording ID, words and stop control alive. The next PCM
packet clears the warning. Silence must still be sent as PCM: quiet speech is not
an end condition. Actual socket/heartbeat failures remain errors; a lost connection
is never treated as a valid end-of-recording signal. Browser foreground restrictions
and bounded resource safeguards still apply.

## Second button click (stop)

Send one UTF-8 text message:

```json
{"type":"end","id":"r1","bytes":32000}
```

`bytes` is the exact total binary PCM byte count since `start`, sent as an integer
and counted with 64-bit totals on both devices (not a 32-bit counter). The example is one
second at 16 kHz. The phone displays partial text as audio arrives. A valid end
flushes the streaming model's final words and finalizes the text. End
with zero bytes displays **No audio received**. Less than 100 ms is too short.

Partial text is displayed only on the phone, never sent back to the MCU.
Wait for ready/result before another press; a new
recording during finalization is ignored. Accepted audio has a bounded phone queue
(30 seconds / 4096 packets); overflow stops intake, drains accepted audio and marks
the result interrupted with a BOOT-stop/retry message. The wire protocol is unchanged: firmware sends every chunk immediately,
never waiting for the second click to upload a whole recording. Button release
has no wire-level effect; click toggling is handled entirely by the firmware.

## Phone/notification stop (optional extension)

Only when `start.control` is `stop-v1`, the phone may send a single unfragmented
UTF-8 text message `STOP <id>`, for example `STOP r1`, with no newline. Included
firmware IDs are `r` plus a nonzero uint32 decimal number (no leading zeroes).
No remote START command exists; only BOOT starts capture. The firmware checks the
active ID, ignores valid stale/duplicate stop requests, and signals its capture task
without doing I2S work in the socket callback. Simultaneous BOOT/remote stops must
not toggle capture back on. Capture flushes its final PCM block, then sends the
normal matching `end` with the exact byte count. That `end` is the acknowledgment;
the app does not stop consuming audio or finalize STT merely because STOP was sent.

After 5 seconds without acknowledgment, the phone offers a stop retry but does not
fabricate an end frame or silently discard speech. A quiet but connected stream
is not forcibly ended. Older firmware without `control` continues to support BOOT stop; the
app does not send it unsupported commands. Both app and firmware should be updated
for phone-stop support. This is a trusted-LAN protocol, not authenticated control.

After a clean, nonempty final transcription, app0.10.0 automatically summarizes it
using the configured API, including in the background. It waits for the model's
final words, not just the wire end. Missing keys, interrupted audio, and silence do
not trigger uploads. Cloud summaries require internet; STT does not.

## Failure and reconnect

Binary before start, nested start, unknown control type, invalid format/rate,
wrong end ID, byte-count mismatch, oversized/unaligned packet, connection/heartbeat
failure, or byte-counter overflow abort the session and reconnect. A transport failure discards incomplete
audio but **retains the latest partial transcript**, checkpointed locally and marked
Interrupted. Reconnect never clears the textbox. A complete
segment already undergoing finalization can finish while transport
reconnects. After reconnect the device must start a **new** recording; it must not
resume an earlier stream.

Retry delays: 1, 2, 4, 8, 15 seconds, then 15 seconds maximum. Successful connection
resets the delay. Explicit disconnect or notification Disconnect cancels retries; Home or
screen-off leaves the foreground connection service running. There is no
DNS or automatic host scan. Private IPv4 endpoints only; OS routing is used for a
phone-hosted hotspot, Wi-Fi-specific sockets for a matching upstream Wi-Fi route.

## Historical automatic discovery (transport-r6/r7 only)

Removed in transport-r8 and current apps. The legacy wire format below is retained
for older firmware reference, not as an active connection path.

While joined to a phone hotspot, the board sends a UTF-8 UDP datagram once per
second to its DHCP gateway (the phone), port 40123, from port 40124:

```json
{"service":"glyph-voice","version":1,"id":"AABBCCDDEEFF","port":8080,"path":"/audio"}
```

`id` is the 12-hex-digit station MAC, stable across DHCP changes. The app accepts
at most 512 bytes, validates service/version/ID/port/path, and derives the endpoint
from the packet sender's private IPv4 address. It ignores packet-supplied host
fields. Up to 16 boards are tracked; announcements expire after eight seconds.
After a three-second search one board connects automatically, or the user selects
from several. A remembered board ID is preferred. Discovery carries no audio,
credentials or transcripts and is not an authentication mechanism. Use a trusted
phone hotspot. Wi-Fi credentials are provisioned separately through the temporary
setup AP; they are never included in these datagrams.
