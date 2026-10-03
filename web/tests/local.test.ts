import { afterEach, expect, it, vi } from 'vitest';
import { LocalApi, LocalRecognizer, validateLocal } from '../src/speech/LocalRecognizer';
import { Session } from '../src/core/Session';
const config = {
  url: 'http://localhost:8765',
  token: 'test-token-not-real-1234567890',
  modelId: 'a'.repeat(32),
  name: 'parakeet',
  bridge: false,
};
const format = { sampleRate: 16000, channels: 1 as const, encoding: 'pcm_s16le' as const };
const id = 'b'.repeat(32);
afterEach(() => vi.unstubAllGlobals());
it('keeps every opening sample while stream startup stalls, even if BOOT stops before it is ready', async () => {
  let start!: (response: Response) => void;
  const received: Uint8Array[] = [];
  const sequences: number[] = [];
  vi.stubGlobal('fetch', async (url: string, request: RequestInit) => {
    if (url.endsWith('/streams'))
      return new Promise<Response>((resolve) => {
        start = resolve;
      });
    if (url.endsWith('/audio')) {
      received.push(new Uint8Array(await (request.body as Blob).arrayBuffer()));
      const sequence = Number((request.headers as Record<string, string>)['X-Audio-Sequence']);
      sequences.push(sequence);
      return new Response(JSON.stringify({ text: 'Opening words retained', sequence }));
    }
    return new Response('{"text":"Opening words retained through the end."}');
  });
  const stop = vi.fn();
  const session = new Session(
    new LocalRecognizer(config),
    () => {},
    () => {},
    stop,
  );
  session.accept({ type: 'start', id: 'first', format, remoteStop: true });
  // 10 seconds of distinct PCM at 20 ms per board packet; the stream-open response
  // remains pending for the entire arrival period. No blank/silent fixture can hide loss.
  const source = Uint8Array.from({ length: 320000 }, (_, i) => (i * 17 + 3) % 251);
  for (let offset = 0; offset < source.length; offset += 640)
    session.accept({ type: 'audio', pcm: source.slice(offset, offset + 640) });
  session.accept({ type: 'end', id: 'first' });
  expect(session.state.bytes).toBe(source.length);
  expect(session.state.phase).toBe('processing');
  expect(received).toHaveLength(0);
  start(new Response(JSON.stringify({ id })));
  await vi.waitFor(() => expect(session.state.phase).toBe('result'));
  const joined = new Uint8Array(received.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of received) {
    joined.set(part, offset);
    offset += part.length;
  }
  expect(joined).toEqual(source);
  expect(sequences).toEqual(Array.from({ length: 50 }, (_, i) => i));
  expect(stop).not.toHaveBeenCalled();
  expect(session.state.conversation?.text).toBe('Opening words retained through the end.');
});
it('rejects URL credentials, paths, query secrets, invalid tokens and HTTPS downgrades', () => {
  for (const url of [
    'http://a:pass@localhost:8765',
    'http://localhost:8765/path',
    'http://localhost:8765?token=secret',
    'file:///tmp',
  ])
    expect(() => validateLocal({ ...config, url })).toThrow();
  expect(() => validateLocal({ ...config, token: 'short' })).toThrow();
  vi.stubGlobal('location', { protocol: 'https:' });
  expect(() => validateLocal(config)).toThrow('HTTPS');
});
it('streams 200ms batches with exact sequence, flushes tail and finalizes once', async () => {
  const calls: { url: string; method: string; sequence?: string; body?: Blob }[] = [];
  const fetch = vi.fn(async (url: string, request: RequestInit) => {
    const headers = request.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer ' + config.token);
    expect(url).not.toContain(config.token);
    expect(request.redirect).toBe('error');
    expect(request.credentials).toBe('omit');
    calls.push({
      url,
      method: request.method!,
      sequence: headers['X-Audio-Sequence'],
      body: request.body as Blob,
    });
    if (url.endsWith('/streams')) return new Response(JSON.stringify({ id }));
    if (url.endsWith('/audio'))
      return new Response(
        JSON.stringify({ text: 'Live words', sequence: Number(headers['X-Audio-Sequence']) }),
      );
    return new Response('{"text":"Live words finalized."}');
  });
  vi.stubGlobal('fetch', fetch);
  const stream = new LocalRecognizer(config).open(format);
  await stream.accept(new Uint8Array(3200));
  expect(calls).toHaveLength(1);
  expect(await stream.accept(new Uint8Array(3200))).toBe('Live words');
  await stream.accept(new Uint8Array(1000));
  expect(await stream.finish()).toBe('Live words finalized.');
  expect(
    calls.filter((c) => c.sequence !== undefined).map((c) => [c.sequence, c.body!.size]),
  ).toEqual([
    ['0', 6400],
    ['1', 1000],
  ]);
  await expect(stream.finish()).rejects.toThrow('no longer active');
});
it('does not silently retry failed audio or switch to cloud', async () => {
  const fetch = vi.fn(
    async (url: string) =>
      new Response(url.endsWith('/streams') ? JSON.stringify({ id }) : 'failure', {
        status: url.endsWith('/streams') ? 200 : 503,
      }),
  );
  vi.stubGlobal('fetch', fetch);
  const stream = new LocalRecognizer(config).open(format);
  await expect(stream.accept(new Uint8Array(6400))).rejects.toThrow('503');
  expect(fetch).toHaveBeenCalledTimes(2);
});
it('waits for outstanding PCM before cancellation and ignores late words', async () => {
  let complete!: (response: Response) => void;
  const fetch = vi.fn(async (url: string, request: RequestInit) => {
    if (url.endsWith('/streams')) return new Response(JSON.stringify({ id }));
    if (request.method === 'DELETE') return new Response('{"ok":true}');
    return await new Promise<Response>((resolve) => {
      complete = resolve;
    });
  });
  vi.stubGlobal('fetch', fetch);
  const stream = new LocalRecognizer(config).open(format);
  const pending = stream.accept(new Uint8Array(6400));
  await vi.waitFor(() => expect(complete).toBeTypeOf('function'));
  stream.cancel();
  expect(fetch).toHaveBeenCalledTimes(2);
  complete(new Response('{"text":"Too late","sequence":0}'));
  await expect(pending).rejects.toThrow('no longer active');
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
  expect(fetch.mock.calls[2][1].method).toBe('DELETE');
});
it('rejects oversized/malformed responses and wrong sequence acknowledgments', async () => {
  for (const reply of ['x'.repeat(1_100_001), 'not json']) {
    vi.stubGlobal('fetch', async () => new Response(reply));
    await expect(new LocalApi(config).request('local')).rejects.toThrow();
  }
  vi.stubGlobal(
    'fetch',
    async (url: string) =>
      new Response(
        url.endsWith('/streams') ? JSON.stringify({ id }) : '{"text":"hi","sequence":3}',
      ),
  );
  await expect(
    new LocalRecognizer(config).open(format).accept(new Uint8Array(6400)),
  ).rejects.toThrow('acknowledgment');
});
