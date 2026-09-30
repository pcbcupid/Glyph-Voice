# Automatic AI summaries on stop

Local transcription uses Parakeet on the phone. Optional cloud speech-to-text is
configured separately in [Speech recognition settings](../kit/APP.md#optional-cloud-transcription)
and sends audio to that endpoint. Summary requests themselves send text only.
Summaries are **online**, optional and powered by your own API account; provider
billing and retention policies apply. The app has no developer-owned API key/backend.

## Setup and use

1. Open the right document/sparkle drawer → **Connect your API**.
2. Select **DeepSeek** or **OpenAI**, paste your personal API key and Save. A blank
   key preserves an existing key for that provider, never another provider's key.
   Remove key deletes that provider's saved credential. Saving does not validate the
   key remotely or incur an API call; an actual request checks account/model access.
3. Default models are `deepseek-flash` and `gpt-4.1-mini`; the field accepts another
   compatible model ID available to your account. No custom base URLs are accepted.
4. Start recording with BOOT. Stop with BOOT again, **Stop & summarize** in the app,
   or **Stop & summarize** in the notification. Phone stop requires transport-r5
   firmware; older firmware gets a useful BOOT-stop/update message.
5. Each clean, nonempty completed recording **automatically sends its final text**
   for summarization once a key is configured, even while locked/backgrounded.
   Phone stop waits for the firmware's end acknowledgment and final recognition;
   it never sends a partial just because the button was tapped. Without credentials,
   the raw text is saved and setup opens when the app is visible; saving a key does
   not retroactively upload earlier recordings. Tap Summarize for existing text.
   Manual summaries send immediately, without a confirmation dialog or opening
   a sidebar. Only that source snapshot is sent, not the whole history or audio.
   Required Android notification/battery permission prompts still apply.
6. The main text panel shows **SUMMARIZING…** with a subtle pulse/spinner, then
   **SUMMARIZED** and the result with a short fade. It is also stored in the right
   drawer. Copy uses the displayed text; Show original transcript returns to the
   source; **Back to summarized** switches back to that exact saved result without
   another API call. Opening a completed summary from history uses this same main panel.
   Starting a new recording or navigating away prevents a late result replacing
   what you are currently reading. AI output can be incorrect.

Both providers use [this natural English-only system prompt](SUMMARY_PROMPT.md), requesting
short flowing paragraphs instead of overview/key-points headings or lists. Non-English
input is translated as it is summarized. Obvious non-Latin-script responses are
rejected rather than saved as English; this is a script check, not a complete language
detector. A model can still misunderstand or mistranslate. There is no automatic
billable retry. Prompt version is included in deduplication so old Chinese or template-style
summaries are not reused for a new request. Existing results are not silently rewritten.

The main panel has its own fixed-height scrolling area. Live text follows the end
unless you scroll up/select text to read. Drawer rows include Delete with confirmation.
Deleting a raw conversation keeps existing summary/source snapshots; deleting a
summary keeps the raw conversation. Deleting an in-flight summary cancels it and
discards its late result. Provider-side copies cannot be retracted by local deletion.

Returning to another app or locking the phone does not intentionally cancel an
in-flight request. There is a foreground status notification and Cancel summary
button. Cancelling cannot retract text already delivered or guarantee no API charge.
Force-stop/process death marks unfinished stored requests interrupted on next launch;
retry is manual, never automatic. Device/OEM power restrictions still apply.

Completion events are tracked separately from latest-only UI state, so returning
to the app cannot retrigger uploads or lose a clean-stop event. Pending automatic
summaries wait for active recognition/another summary to finish (up to 8 pending;
excess stays in raw history with a warning). Disconnect/cancel clears pending
automatic uploads; deleted raw entries are skipped. A force-stop loses pending
automatic jobs but not committed raw history. Errors are never automatically retried.
Silence, short invalid recordings, disconnects and overloaded/interrupted speech
are not automatically uploaded. Remove the selected provider's key to stop future
automatic uploads. Provider billing applies to each new request.

## Failure and scope

- Unauthorized key/model, insufficient credit, quota/rate limits, network failure,
  timeout, unreadable/oversized responses, refused or truncated output are errors,
  not successful summaries. No raw provider error body is displayed or logged.
- Calls time out after 120 seconds. No automatic network retries or redirects.
  Identical successful source/provider/model/prompt-version requests reuse the saved result.
  A different source snapshot or provider/model never replaces an earlier result.
- Up to 120,000 source characters and 2,048 output tokens per request. Oversized
  input is rejected **before sending**, never silently truncated. These are summary
  request limits, not a transcription/recording duration limit. No chunked summaries
  or custom provider URLs are implemented in this version.
- Existing placeholder requests migrate without losing their source text and are
  shown as **Not generated**. Failed summaries keep their source for manual retry.
- Keys are encrypted using Android Keystore AES-GCM with provider-bound authenticated
  data. Backups, autofill and key-field saved state are disabled; keys are not shown
  again, logged or embedded in the APK. Editing dialogs clear on leaving the screen.
  Credentials must exist briefly in process memory to send an authenticated request;
  this cannot protect against a rooted/compromised phone. Use limited personal keys,
  provider spending limits and revoke lost-device keys. Never ship a shared paid key.
- OpenAI requests specify `store:false`. This is not a promise of zero provider
  retention; the provider's account policies still apply. DeepSeek receives ordinary
  text chat-completion requests with thinking disabled. No tools/files/audio are sent.
- Local transcription needs only local Wi-Fi. AI summaries also need working internet
  (for example mobile data while the phone hosts the Glyph hotspot). Summary failure
  cannot change the audio stream or raw transcript.

## Official references used

- [DeepSeek chat-completion schema and model IDs](https://api-docs.deepseek.com/api/create-chat-completion/)
- [OpenAI text generation / Responses API](https://developers.openai.com/api/docs/guides/text)
- [GPT-4.1 mini availability and capabilities](https://developers.openai.com/api/docs/models/gpt-4.1-mini)
- [Android foreground-service types, including dataSync](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android Doze and battery-exemption behavior](https://developer.android.com/training/monitoring-device-state/doze-standby)

Requests/parsers are verified with a local mock provider, not a real paid account.
No real API key or transcript was sent to a provider during implementation.
