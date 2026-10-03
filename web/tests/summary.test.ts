import { afterEach, describe, expect, it, vi } from 'vitest';
import { SummaryClient, type SummaryConfig } from '../src/ai/SummaryClient';
const config: SummaryConfig = {
  server: { url: 'http://localhost:8765', token: 'test-server-token-1234567890' },
  provider: 'deepseek',
  model: 'deepseek-flash',
  key: 'test-provider-key',
  automatic: true,
  consent: true,
};
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
describe('text summary relay client', () => {
  it('validates consent and credentials before sending', () => {
    expect(() => new SummaryClient({ ...config, consent: false })).toThrow(/sharing/);
    expect(() => new SummaryClient({ ...config, key: 'bad\nkey' })).toThrow(/key/);
    expect(
      () =>
        new SummaryClient({
          ...config,
          server: { ...config.server, url: 'http://localhost:8765/?key=secret' },
        }),
    ).toThrow();
  });
  it('sends only text to authenticated local server, never provider directly', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ text: 'A natural summary.' }));
    vi.stubGlobal('fetch', fetch);
    expect(
      await new SummaryClient(config).summarize('My words.', new AbortController().signal),
    ).toBe('A natural summary.');
    const [url, options] = fetch.mock.calls[0];
    expect(url).toBe('http://localhost:8765/api/summary');
    expect(options.headers.Authorization).toBe('Bearer ' + config.server.token);
    expect(JSON.parse(options.body)).toEqual({
      provider: config.provider,
      model: config.model,
      key: config.key,
      text: 'My words.',
      consent: true,
    });
    expect(options.redirect).toBe('error');
    expect(options.cache).toBe('no-store');
  });
  it('rejects empty and oversized text without fetching', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    for (const text of ['', '中'.repeat(16001)])
      await expect(
        new SummaryClient(config).summarize(text, new AbortController().signal),
      ).rejects.toThrow(/48 KB/);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('reports provider errors without automatic retry', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(Response.json({ error: 'API key rejected.' }, { status: 400 }));
    vi.stubGlobal('fetch', fetch);
    await expect(
      new SummaryClient(config).summarize('words', new AbortController().signal),
    ).rejects.toThrow(/key rejected/);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('handles unreadable, empty and oversized responses', async () => {
    for (const response of [
      new Response('bad-json'),
      Response.json(null),
      Response.json({ text: '' }),
      new Response('x'.repeat(1_100_001)),
    ]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response));
      await expect(
        new SummaryClient(config).summarize('words', new AbortController().signal),
      ).rejects.toThrow();
    }
  });
  it('explains old backend and local-token errors even for non-JSON responses', async () => {
    for (const [status, expected] of [
      [404, 'restart start-web'],
      [405, 'restart start-web'],
      [401, 'different from the provider API key'],
    ] as const) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('old server', { status })));
      await expect(
        new SummaryClient(config).summarize('words', new AbortController().signal),
      ).rejects.toThrow(expected);
    }
  });
  it('does not cut off a provider response that fits Androids 120-second timeout', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url, options) =>
          new Promise((resolve, reject) => {
            const timer = setTimeout(
              () => resolve(Response.json({ text: 'A slower summary.' })),
              110_000,
            );
            options.signal.addEventListener('abort', () => {
              clearTimeout(timer);
              reject(new DOMException('Aborted', 'AbortError'));
            });
          }),
      ),
    );
    const result = new SummaryClient(config).summarize('words', new AbortController().signal);
    const check = expect(result).resolves.toBe('A slower summary.');
    await vi.advanceTimersByTimeAsync(110_000);
    await check;
  });
  it('cancels pending requests without a retry', async () => {
    const fetch = vi.fn(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options.signal.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError')),
          );
        }),
    );
    vi.stubGlobal('fetch', fetch);
    const abort = new AbortController();
    const request = new SummaryClient(config).summarize('words', abort.signal);
    abort.abort();
    await expect(request).rejects.toThrow(/cancelled/);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
