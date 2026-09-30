import { afterEach, describe, expect, it, vi } from 'vitest';
import { CloudRecognizer, encodeWav, validateCloud } from '../src/speech/CloudRecognizer';
const config = {
  endpoint: 'https://speech.example/transcriptions',
  model: 'speech-model',
  key: 'test-key-not-real',
};
const format = { sampleRate: 16000, channels: 1 as const, encoding: 'pcm_s16le' as const };
afterEach(() => vi.unstubAllGlobals());
describe('opt-in cloud audio', () => {
  it('encodes mono PCM16 WAV headers and exact PCM payload', () => {
    const wav = encodeWav(new Uint8Array([1, 2, 3, 4]), 16000);
    const view = new DataView(wav.buffer);
    expect(new TextDecoder().decode(wav.subarray(0, 4))).toBe('RIFF');
    expect(view.getUint32(24, true)).toBe(16000);
    expect(view.getUint32(40, true)).toBe(4);
    expect([...wav.subarray(44)]).toEqual([1, 2, 3, 4]);
  });
  it.each([
    'http://speech.example',
    'https://user:secret@speech.example',
    'https://speech.example?q=key',
    'https://speech.example/#key',
  ])('rejects unsafe endpoint %s', (endpoint) => {
    expect(() => validateCloud({ ...config, endpoint })).toThrow();
  });
  it('uploads at 15 seconds and flushes a short final tail; key not in URL', async () => {
    const fetch = vi.fn().mockImplementation(async () => new Response('{"text":"hello"}'));
    vi.stubGlobal('fetch', fetch);
    const stream = new CloudRecognizer(config).open(format);
    await stream.accept(new Uint8Array(32000));
    expect(fetch).not.toHaveBeenCalled();
    expect(await stream.accept(new Uint8Array(32000 * 14))).toBe('hello');
    expect(fetch).toHaveBeenCalledTimes(1);
    const request = fetch.mock.calls[0][1];
    expect(request.redirect).toBe('error');
    expect(request.credentials).toBe('omit');
    expect(request.headers.Authorization).toBe('Bearer test-key-not-real');
    expect(request.body.get('file').size).toBe(480044);
    await stream.accept(new Uint8Array(3200));
    expect(await stream.finish()).toBe('hello hello');
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it('does not retry provider errors or reveal provider bodies', async () => {
    const fetch = vi.fn(async () => new Response('secret detail', { status: 401 }));
    vi.stubGlobal('fetch', fetch);
    const stream = new CloudRecognizer(config).open(format);
    await stream.accept(new Uint8Array(3200));
    await expect(stream.finish()).rejects.toThrow('HTTP 401');
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('rejects oversized or malformed responses', async () => {
    for (const response of ['x'.repeat(65537), '{}', 'not-json']) {
      vi.stubGlobal('fetch', async () => new Response(response));
      const stream = new CloudRecognizer(config).open(format);
      await stream.accept(new Uint8Array(3200));
      await expect(stream.finish()).rejects.toThrow();
    }
  });
  it('cancels in-flight upload and rejects further audio', async () => {
    vi.stubGlobal(
      'fetch',
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal!.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError')),
          );
        }),
    );
    const stream = new CloudRecognizer(config).open(format);
    await stream.accept(new Uint8Array(3200));
    const finishing = stream.finish();
    stream.cancel();
    await expect(finishing).rejects.toThrow();
    await expect(stream.accept(new Uint8Array(2))).rejects.toThrow();
  });
});
