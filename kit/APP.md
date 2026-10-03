# Android app

## Install and connect

For the current Wi-Fi-only/manual-IP flow, build the latest APK from source.
Older files on [Releases](https://github.com/pcbcupid/Glyph-Voice/releases) may still
use discovery/Bluetooth; do not mix those instructions with this version.
Allow installation from the browser/file manager when Android asks, then open
**GLYPH VOICE**. The package ID remains `com.pcbcupid.voice`.

Enable the configured 2.4 GHz phone hotspot or join the same router as the Glyph.
First-time board setup now asks for its own hotspot label/password in browser
serial before router setup: [no-Arduino setup](FIRMWARE.md). GPIO14 blinking means
no Wi-Fi IP; steady means Wi-Fi joined, not necessarily app/model ready.
Open USB Serial Monitor at 115200 baud and copy the board's `[ready]` recording IP.
Tap **Connect Glyph**, enter that IP (optionally `:8080`), then **Connect**.
Grant notifications and review background/battery prompts. The app remembers the
entered IP; update it if DHCP changes it. No Bluetooth/location permission or
nearby-board scanning is requested. Only one app can use a board at a time.

Local mode prepares the bundled Parakeet model on first connection and shows
progress. Allow at least 2 GB free storage. The bundled model is English-only.
There is no first-run internet download. A 64-bit Android 8+ device is required.

## Recording and history

- Click the board's **BOOT** button once to start. Release it and speak.
- Click **BOOT again**, or **Stop & summarize** in the app/notification, to finish.
- Wait for finalization before beginning another recording.
- The left drawer contains saved conversations; the right drawer contains summaries
  and settings. Copy copies the displayed text. Delete is explicit.
- Partial text is saved as it changes. An interrupted recording remains marked
  interrupted; it is not presented as complete or automatically summarized.
- Recording and processing can continue with the screen locked while the foreground
  service runs, subject to the phone manufacturer's power restrictions.

## Optional cloud transcription

Cloud setup is available **after installation**, and may be skipped entirely.
Disconnect Glyph first. Open the right drawer → **Speech recognition · Local / Cloud**.

1. Enable **Use cloud speech-to-text**.
2. Enter the **full HTTPS transcription URL**, such as
   `https://your-provider.example/v1/audio/transcriptions` (replace with your actual
   provider; the example is not a working service).
3. Enter a speech model ID supported by that endpoint.
4. Enter its API key if required, then Save. Saving does not send audio or validate
   account access. Connect Glyph and make a short recording to test the endpoint.

The supported contract is an HTTP POST with multipart fields `model`,
`response_format=json`, and `file` (PCM16 mono WAV). The response must be JSON
containing a string `text`. This follows the [OpenAI-compatible file transcription
format](https://developers.openai.com/api/docs/guides/speech-to-text); chat-completion,
Realtime/WebSocket, and arbitrary proprietary speech URLs are not interchangeable.
URLs must use HTTPS and cannot contain user credentials, query parameters, or fragments.
A custom provider requiring different fields/authentication needs an adapter.

Audio is uploaded in **15-second chunks while recording**, plus the remaining
short chunk after stop. Text appears after each response; this mode is not
word-by-word realtime transcription. Independent chunk boundaries can affect
word accuracy. Requests have a 25-second timeout, no automatic retries, and no
redirect following. A persistent slow provider can exhaust the bounded 30-second
audio queue and interrupt the recording; previously returned words remain saved.
No recording-duration limit is imposed, but device/provider resources still apply.

Cloud mode does not load/extract Parakeet. The APK still includes the model so
switching back to local works offline. Reopen settings while disconnected and
turn cloud off to return to local recognition. There is no silent fallback or
upload in local mode.

API keys are encrypted using Android Keystore. Leaving the key blank keeps it
only when the URL is unchanged; changing URL clears the old key. Use the remove
checkbox to delete a saved key. Backups and key-field saved state are disabled.
Cloud audio is held in RAM, not saved as a file; the provider receives it and its
billing/retention rules apply. Cancelling cannot retract data already delivered.
Transcription uses the phone's normal internet route; the board connection stays
on the local hotspot route. Mobile data is commonly needed while hosting a hotspot.

## Optional summaries

**Connect your API** configures a separate DeepSeek/OpenAI summary account. Once
configured, stopping a clean, nonempty recording automatically sends its final
text for summarization, in either speech mode. Removing the selected summary key
stops automatic summaries. Speech keys and summary keys are stored separately.
See [summary setup, behavior and privacy](../docs/AI.md).

## Updates

The public release keeps the existing development signing certificate so installs
signed by that same key can upgrade without clearing app history. A build signed
with a different key cannot replace it. Keep the maintainer's private signing
keystore backed up for future releases; it is not in GitHub.
