import type { AudioFormat, SpeechRecognizer, SpeechStream } from '../core/types';

export interface LocalConnection {
  url: string;
  token: string;
}
export interface LocalConfig extends LocalConnection {
  modelId: string;
  name: string;
  bridge: boolean;
}
export interface FolderListing {
  path: string;
  parent: string | null;
  folders: { name: string; path: string }[];
  compatibleFiles: boolean;
}
export interface LocalInfo {
  model: string | null;
  modelId: string | null;
  active: boolean;
  glyphBridge?: boolean;
}

export function validateLocal(config: LocalConnection): LocalConnection {
  const url = new URL(config.url.trim());
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/'
  )
    throw new Error('Use the self-hosted server origin only, such as http://localhost:8765.');
  if (
    typeof location !== 'undefined' &&
    location.protocol === 'https:' &&
    url.protocol !== 'https:'
  )
    throw new Error(
      'An HTTPS page needs an HTTPS local server. Open the app served by your backend.',
    );
  if (!/^[A-Za-z0-9_-]{24,256}$/.test(config.token))
    throw new Error('Enter the private access token printed by your local server.');
  return { url: url.origin, token: config.token };
}

export class LocalApi {
  readonly config: LocalConnection;
  constructor(config: LocalConnection) {
    this.config = validateLocal(config);
  }
  async request<T>(
    path: string,
    method = 'GET',
    body?: unknown,
    sequence?: number,
    timeoutMs = 35000,
  ): Promise<T> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const binary = body instanceof Uint8Array;
      const response = await fetch(this.config.url + '/api/' + path, {
        method,
        headers: {
          Authorization: `Bearer ${this.config.token}`,
          ...(body !== undefined
            ? { 'Content-Type': binary ? 'application/octet-stream' : 'application/json' }
            : {}),
          ...(sequence !== undefined ? { 'X-Audio-Sequence': String(sequence) } : {}),
        },
        body:
          body === undefined
            ? undefined
            : binary
              ? new Blob([new Uint8Array(body)])
              : JSON.stringify(body),
        signal: controller.signal,
        credentials: 'omit',
        redirect: 'error',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
      });
      if (!response.ok)
        throw new Error(
          response.status === 401
            ? 'Local server token was rejected.'
            : response.status === 409
              ? 'Local worker is busy. Stop other sessions and try again.'
              : `Local server returned HTTP ${response.status}. Check the model, server console and session; audio is not retried.`,
        );
      if (!response.body) throw new Error('Empty local server response.');
      const reader = response.body.getReader(),
        decoder = new TextDecoder();
      let text = '',
        count = 0;
      try {
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          count += value.length;
          if (count > 1_100_000) {
            await reader.cancel();
            throw new Error('Local response too large.');
          }
          text += decoder.decode(value, { stream: true });
        }
      } finally {
        reader.releaseLock();
      }
      return JSON.parse(text + decoder.decode()) as T;
    } catch (error) {
      if (controller.signal.aborted || error instanceof TypeError)
        throw new Error(
          'Local server unavailable or timed out. Check its address, allowed origin and network. No cloud fallback was used.',
        );
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }
}

function transcript(value: { text: string }): string {
  if (typeof value?.text !== 'string') throw new Error('Invalid local transcript response.');
  return value.text;
}

export class LocalRecognizer implements SpeechRecognizer {
  private api: LocalApi;
  constructor(private config: LocalConfig) {
    this.api = new LocalApi(config);
    if (!/^[a-f0-9]{32}$/.test(config.modelId))
      throw new Error('Choose and load a local model folder first.');
  }
  open(format: AudioFormat): SpeechStream {
    return new LocalStream(this.api, this.config.modelId, format.sampleRate);
  }
}

class LocalStream implements SpeechStream {
  private pending: Uint8Array;
  private used = 0;
  private sequence = 0;
  private text = '';
  private cancelled = false;
  private finished = false;
  private started: Promise<string>;
  private inFlight: Promise<unknown> = Promise.resolve();
  constructor(
    private api: LocalApi,
    modelId: string,
    rate: number,
  ) {
    this.pending = new Uint8Array(Math.round(rate / 5) * 2); // 200 ms transport batches; model retains streaming state.
    this.started = api
      .request<{ id: string }>('streams', 'POST', { modelId, sampleRate: rate })
      .then((result) => {
        if (!/^[a-f0-9]{32}$/.test(result.id)) throw new Error('Invalid local stream ID.');
        return result.id;
      });
    void this.started.catch(() => {}); // Report errors through accept/finish, never an unhandled start rejection.
  }
  private check() {
    if (this.cancelled || this.finished) throw new Error('Local recording no longer active.');
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
      if (this.used === this.pending.length) await this.flush();
    }
    return this.text;
  }
  private async flush() {
    this.check();
    if (!this.used) return;
    const id = await this.started;
    this.check();
    const bytes = this.pending.slice(0, this.used),
      sequence = this.sequence;
    try {
      const request = this.api.request<{ text: string; sequence: number }>(
        `streams/${id}/audio`,
        'POST',
        bytes,
        sequence,
      );
      this.inFlight = request;
      const result = await request;
      this.check();
      if (result.sequence !== sequence) throw new Error('Local audio acknowledgment mismatch.');
      this.text = transcript(result);
      this.sequence++;
    } finally {
      bytes.fill(0);
      this.pending.fill(0);
      this.used = 0;
    }
  }
  async finish(): Promise<string> {
    this.check();
    await this.flush();
    const id = await this.started;
    this.check();
    const request = this.api.request<{ text: string }>(`streams/${id}/finish`, 'POST', {
      sequence: this.sequence,
    });
    this.inFlight = request;
    const result = await request;
    this.check();
    this.finished = true;
    this.pending.fill(0);
    return transcript(result);
  }
  cancel() {
    if (this.cancelled || this.finished) return;
    this.cancelled = true;
    this.pending.fill(0);
    this.used = 0;
    // Release AFTER the one outstanding operation; no concurrent DELETE/PCM race.
    void this.inFlight
      .catch(() => {})
      .then(() => this.started)
      .then((id) => this.api.request(`streams/${id}`, 'DELETE'))
      .catch(() => {});
    // If the tab/process vanishes, the backend expires the idle stream after 60 seconds.
  }
}
