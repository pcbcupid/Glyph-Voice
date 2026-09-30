import type { AudioFormat, SpeechRecognizer, SpeechStream } from '../core/types';

export interface CloudConfig {
  endpoint: string;
  model: string;
  key: string;
}
export function validateCloud(config: CloudConfig): CloudConfig {
  let url: URL;
  try {
    url = new URL(config.endpoint.trim());
  } catch {
    throw new Error('Enter a full HTTPS transcription URL.');
  }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash)
    throw new Error('Use HTTPS without URL credentials, query parameters or fragments.');
  if (!/^[A-Za-z0-9._:/-]{1,120}$/.test(config.model.trim()))
    throw new Error('Enter a valid speech model ID.');
  if (config.key && !/^[!-~]{1,1024}$/.test(config.key.trim()))
    throw new Error('Check the API key.');
  return { endpoint: url.href, model: config.model.trim(), key: config.key.trim() };
}
export function encodeWav(pcm: Uint8Array, rate: number): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(44 + pcm.length),
    view = new DataView(out.buffer);
  const label = (offset: number, text: string) => out.set(new TextEncoder().encode(text), offset);
  label(0, 'RIFF');
  view.setUint32(4, 36 + pcm.length, true);
  label(8, 'WAVEfmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, rate, true);
  view.setUint32(28, rate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  label(36, 'data');
  view.setUint32(40, pcm.length, true);
  out.set(pcm, 44);
  return out;
}
export class CloudRecognizer implements SpeechRecognizer {
  private config: CloudConfig;
  constructor(config: CloudConfig) {
    this.config = validateCloud(config);
  }
  open(format: AudioFormat): SpeechStream {
    return new CloudStream(this.config, format.sampleRate);
  }
}
class CloudStream implements SpeechStream {
  private pending: Uint8Array;
  private used = 0;
  private text = '';
  private cancelled = false;
  private finished = false;
  private active?: AbortController;
  constructor(
    private config: CloudConfig,
    private rate: number,
  ) {
    this.pending = new Uint8Array(rate * 2 * 15);
  }
  private check() {
    if (this.cancelled || this.finished) throw new Error('Recording no longer active.');
  }
  async accept(pcm: Uint8Array): Promise<string> {
    this.check();
    if (pcm.length % 2) throw new Error('Unaligned PCM16.');
    for (let offset = 0; offset < pcm.length;) {
      this.check();
      const count = Math.min(pcm.length - offset, this.pending.length - this.used);
      this.pending.set(pcm.subarray(offset, offset + count), this.used);
      this.used += count;
      offset += count;
      if (this.used === this.pending.length) await this.upload();
    }
    return this.text;
  }
  private async upload() {
    this.check();
    if (!this.used) return;
    const wav = encodeWav(this.pending.subarray(0, this.used), this.rate);
    const abort = new AbortController();
    this.active = abort;
    const timeout = setTimeout(() => abort.abort(), 25000);
    try {
      const body = new FormData();
      body.set('model', this.config.model);
      body.set('response_format', 'json');
      body.set('file', new Blob([wav], { type: 'audio/wav' }), 'speech.wav');
      const response = await fetch(this.config.endpoint, {
        method: 'POST',
        body,
        headers: this.config.key ? { Authorization: `Bearer ${this.config.key}` } : {},
        signal: abort.signal,
        credentials: 'omit',
        redirect: 'error',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
      });
      if (!response.ok)
        throw new Error(
          `Cloud speech returned HTTP ${response.status}. Check your endpoint, key and quota.`,
        );
      if (!response.body) throw new Error('Empty cloud response.');
      const reader = response.body.getReader();
      let raw = '',
        size = 0;
      const decoder = new TextDecoder();
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          size += value.length;
          if (size > 65536) {
            await reader.cancel();
            throw new Error('Cloud response exceeds 64 KB.');
          }
          raw += decoder.decode(value, { stream: true });
        }
      } finally {
        reader.releaseLock();
      }
      this.check();
      let value;
      try {
        value = JSON.parse(raw + decoder.decode());
      } catch {
        throw new Error('Cloud response was not valid JSON.');
      }
      if (typeof value?.text !== 'string') throw new Error('Cloud response needs a text field.');
      if (value.text.trim()) this.text += (this.text ? ' ' : '') + value.text.trim();
    } catch (e) {
      if (e instanceof TypeError || abort.signal.aborted)
        throw new Error(
          'Cloud request blocked, offline or timed out. The provider must allow browser CORS. No automatic retry; the request may have been billed.',
        );
      throw e;
    } finally {
      clearTimeout(timeout);
      this.active = undefined;
      wav.fill(0);
      this.pending.fill(0);
      this.used = 0;
    }
  }
  async finish() {
    this.check();
    await this.upload();
    this.finished = true;
    return this.text;
  }
  cancel() {
    this.cancelled = true;
    this.active?.abort();
    this.pending.fill(0);
  }
}
