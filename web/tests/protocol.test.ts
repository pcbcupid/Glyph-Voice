import { describe, expect, it } from 'vitest';
import { GlyphProtocol, localEndpoint } from '../src/core/protocol';

const start = {
  type: 'start',
  version: 1,
  id: 'r1',
  sampleRate: 16000,
  channels: 1,
  encoding: 'pcm_s16le',
  control: 'stop-v1',
};
describe('Glyph wire contract', () => {
  it('reads real protocol metadata, binary PCM and end count; advertises remote stop', () => {
    const p = new GlyphProtocol();
    expect(p.accept(JSON.stringify(start))).toMatchObject({
      type: 'start',
      format: { sampleRate: 16000 },
      remoteStop: true,
    });
    expect(p.stopCommand()).toBe('STOP r1');
    expect(p.accept(new ArrayBuffer(640)).type).toBe('audio');
    expect(p.accept('{"type":"end","id":"r1","bytes":640}')).toEqual({ type: 'end', id: 'r1' });
    expect(p.stopCommand()).toBeNull();
  });
  it.each([8000, 16000, 32000, 44100, 48000])(
    'supports declared rate %i without guessing',
    (rate) => {
      expect(
        new GlyphProtocol().accept(JSON.stringify({ ...start, sampleRate: rate })),
      ).toMatchObject({ format: { sampleRate: rate } });
    },
  );
  it.each([
    { sampleRate: 22050 },
    { channels: 2 },
    { encoding: 'float32' },
    { id: 'r1\nSTOP r2' },
    { version: '1' },
    { sampleRate: '16000' },
  ])('rejects invalid metadata %j', (change) => {
    expect(() => new GlyphProtocol().accept(JSON.stringify({ ...start, ...change }))).toThrow();
  });
  it('rejects PCM before start and duplicate start', () => {
    const p = new GlyphProtocol();
    expect(() => p.accept(new ArrayBuffer(2))).toThrow('before start');
    p.accept(JSON.stringify(start));
    expect(() => p.accept(JSON.stringify(start))).toThrow('Duplicate');
  });
  it.each([0, 1, 3, 16386])('rejects a %i-byte packet', (size) => {
    const p = new GlyphProtocol();
    p.accept(JSON.stringify(start));
    expect(() => p.accept(new ArrayBuffer(size))).toThrow();
  });
  it.each([{ bytes: '0' }, { bytes: 2 }, { id: 'r2' }])('rejects mismatched end %j', (change) => {
    const p = new GlyphProtocol();
    p.accept(JSON.stringify(start));
    expect(() =>
      p.accept(JSON.stringify({ type: 'end', id: 'r1', bytes: 0, ...change })),
    ).toThrow();
  });
  it('treats older firmware stop support as absent', () => {
    const p = new GlyphProtocol();
    p.accept(JSON.stringify({ ...start, control: undefined }));
    expect(p.stopCommand()).toBeNull();
  });
  it('rejects malformed, oversized and unknown controls', () => {
    for (const value of ['{', 'null', '{}', ' '.repeat(2049)])
      expect(() => new GlyphProtocol().accept(value)).toThrow();
  });
});
describe('LAN endpoint boundary', () => {
  it.each(['10.0.0.1', '172.16.0.2', '192.168.4.2', '169.254.2.1'])('accepts %s', (address) => {
    expect(localEndpoint(address, 'http:')).toBe(`ws://${address}:8080/audio`);
  });
  it.each([
    '8.8.8.8',
    'example.com',
    '127.0.0.1',
    '192.168.1.300',
    '192.168.01.1',
    '10.0.0.1:65536',
    '10.0.0.1:0',
    '10.0.0.1@evil.example',
  ])('rejects %s', (address) => {
    expect(() => localEndpoint(address, 'http:')).toThrow();
  });
  it('explains HTTPS/plain-WebSocket incompatibility rather than silently failing', () => {
    expect(() => localEndpoint('192.168.4.1', 'https:')).toThrow('secure board transport');
  });
});
