import type { AudioEvent, AudioFormat } from './types';

/** Same contract as docs/PROTOCOL.md; no dependency on React or browser globals. */
export function localEndpoint(address: string, pageProtocol: string): string {
  const match = /^(\d{1,3}(?:\.\d{1,3}){3})(?::([1-9]\d{0,4}))?$/.exec(address.trim());
  if (!match) throw new Error('Enter a local IPv4 address, optionally followed by :port.');
  const octets = match[1].split('.');
  if (octets.some((v) => Number(v) > 255 || String(Number(v)) !== v))
    throw new Error('Invalid IPv4 address.');
  const [a, b] = octets.map(Number);
  if (!(
    a === 10 ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 169 && b === 254)
  ))
    throw new Error('Use the Glyph’s private local-network address.');
  const port = Number(match[2] ?? 8080);
  if (port > 65535) throw new Error('Invalid port.');
  // Conservative across browsers: do not rely on Chrome-only mixed-content exemptions.
  if (pageProtocol === 'https:')
    throw new Error(
      'This HTTPS page needs a secure board transport. Configure a local model server with --glyph-host, or use same-network HTTP development.',
    );
  return `ws://${match[1]}:${port}/audio`;
}

export class GlyphProtocol {
  private active: { id: string; bytes: number; remoteStop: boolean } | null = null;
  reset() {
    this.active = null;
  }
  get recording() {
    return this.active !== null;
  }
  stopCommand(): string | null {
    return this.active?.remoteStop ? `STOP ${this.active.id}` : null;
  }
  accept(data: string | ArrayBuffer): AudioEvent {
    if (typeof data !== 'string') {
      if (!this.active) throw new Error('PCM arrived before start.');
      if (data.byteLength < 2 || data.byteLength > 16384 || data.byteLength % 2)
        throw new Error('Invalid PCM packet size or alignment.');
      const total = this.active.bytes + data.byteLength;
      if (!Number.isSafeInteger(total)) throw new Error('Recording byte count overflow.');
      this.active.bytes = total;
      return { type: 'audio', pcm: new Uint8Array(data) };
    }
    if (data.length > 2048) throw new Error('Control message too large.');
    let value;
    try {
      value = JSON.parse(data);
    } catch {
      throw new Error('Malformed control JSON.');
    }
    if (!value || typeof value !== 'object') throw new Error('Expected a control object.');
    if (value.type === 'start') {
      if (this.active) throw new Error('Duplicate start.');
      if (value.version !== 1) throw new Error('Expected protocol version 1.');
      if (typeof value.id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(value.id))
        throw new Error('Invalid recording ID.');
      if (
        value.channels !== 1 ||
        value.encoding !== 'pcm_s16le' ||
        ![8000, 16000, 32000, 44100, 48000].includes(value.sampleRate)
      )
        throw new Error('Expected mono PCM16 at a supported sample rate.');
      const remoteStop = value.control === 'stop-v1';
      this.active = { id: value.id, bytes: 0, remoteStop };
      const format: AudioFormat = {
        sampleRate: value.sampleRate,
        channels: 1,
        encoding: 'pcm_s16le',
      };
      return { type: 'start', id: value.id, format, remoteStop };
    }
    if (value.type === 'end') {
      if (!this.active || value.id !== this.active.id)
        throw new Error('End does not match recording.');
      if (!Number.isSafeInteger(value.bytes) || value.bytes !== this.active.bytes)
        throw new Error('Audio byte count mismatch.');
      this.reset();
      return { type: 'end', id: value.id };
    }
    throw new Error('Unknown control type.');
  }
}
