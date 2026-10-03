import { expect, test, type Page, type Route, type WebSocketRoute } from '@playwright/test';

const token = 'test-server-token-1234567890';
const key = 'test-summary-key-never-persist';
const finalWords = 'We agreed to meet tomorrow. Bring the prototype.';
const answer = 'The team will meet tomorrow with the prototype.';

async function setup(page: Page, summary: (route: Route) => Promise<void>) {
  const stops: string[] = [];
  let socket!: WebSocketRoute;
  await page.route('**/api/**', async (route) => {
    expect(route.request().headers().authorization).toBe('Bearer ' + token);
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/summary') return summary(route);
    const body =
      path === '/api/local' || path === '/api/model'
        ? { model: 'Parakeet', modelId: 'a'.repeat(32), active: false, glyphBridge: true }
        : path === '/api/folders'
          ? { path: '.', parent: null, folders: [], compatibleFiles: true }
          : path === '/api/streams'
            ? { id: 'b'.repeat(32) }
            : path.endsWith('/audio')
              ? {
                  text: 'We agreed to meet tomorrow.',
                  sequence: Number(route.request().headers()['x-audio-sequence']),
                }
              : { text: finalWords };
    await route.fulfill({ json: body });
  });
  await page.routeWebSocket('ws://127.0.0.1:4173/api/glyph', (ws) => {
    socket = ws;
    ws.onMessage((message) => {
      if (String(message).startsWith('{')) ws.send('{"proxy":"ready"}');
      else {
        stops.push(String(message));
        ws.send('{"type":"end","id":"r1","bytes":6400}');
      }
    });
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Connect Glyph' }).click();
  await page.getByLabel('Glyph address').fill('192.168.4.2:8080');
  await page.getByRole('button', { name: 'Next: speech recognition' }).click();
  await page.getByLabel('Local server access token').fill(token);
  await page.getByRole('checkbox', { name: /I trust this server/ }).check();
  await page.getByRole('button', { name: 'Choose model folder' }).click();
  await page.getByRole('button', { name: 'Load this model locally' }).click();
  await expect(page.getByText('Glyph connected', { exact: true })).toBeVisible();
  return {
    stops,
    start: async () => {
      socket.send(
        JSON.stringify({
          type: 'start',
          version: 1,
          id: 'r1',
          sampleRate: 16000,
          channels: 1,
          encoding: 'pcm_s16le',
          control: 'stop-v1',
        }),
      );
      socket.send(Buffer.alloc(6400));
      await expect(page.getByRole('region', { name: 'Transcript text' })).toContainText(
        'We agreed',
      );
    },
    end: () => socket.send('{"type":"end","id":"r1","bytes":6400}'),
  };
}
async function saveSettings(page: Page) {
  await page.getByLabel('Summary API key', { exact: true }).fill(key);
  await page.getByRole('checkbox', { name: /I allow transcript text/ }).check();
  await page.getByRole('button', { name: 'Save summary settings' }).click();
}
async function configure(page: Page) {
  await page.getByRole('button', { name: 'Open summaries' }).click();
  await page.getByRole('button', { name: /Connect your API/ }).click();
  await saveSettings(page);
}

test('provider failure keeps final transcript and does not retry automatically', async ({
  page,
}) => {
  let calls = 0;
  const board = await setup(page, async (route) => {
    calls++;
    await route.fulfill({
      status: 400,
      json: { error: 'API key rejected. Check Connect your API.' },
    });
  });
  await configure(page);
  await board.start();
  board.end();
  await expect(page.getByRole('status')).toContainText('API key rejected');
  await expect(page.getByRole('complementary', { name: 'Summary status' })).toContainText(
    'API key rejected',
  );
  await expect(page.getByRole('button', { name: 'Check summary settings' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toHaveText(finalWords);
  await page.getByRole('button', { name: 'Open summaries' }).click();
  await expect(page.locator('.history-row')).toHaveCount(0);
  expect(calls).toBe(1);
});

test('a completed summary remains visible when browser history cannot save it', async ({
  page,
}) => {
  await page.addInitScript(() => {
    const original = IDBDatabase.prototype.transaction;
    IDBDatabase.prototype.transaction = function (...args: Parameters<typeof original>) {
      if (args[0] === 'summaries' && args[1] === 'readwrite')
        throw new DOMException('Simulated full storage', 'QuotaExceededError');
      return original.apply(this, args);
    };
  });
  const board = await setup(page, async (route) => {
    await route.fulfill({ json: { text: answer } });
  });
  await configure(page);
  await board.start();
  board.end();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toHaveText(answer);
  await expect(page.getByRole('complementary', { name: 'Summary status' })).toContainText(
    'could not save',
  );
  await page.getByRole('button', { name: 'Show original transcript' }).click();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toHaveText(finalWords);
});

test('late summary never replaces a newer live recording', async ({ page }) => {
  let release!: () => void;
  const waiting = new Promise<void>((resolve) => {
    release = resolve;
  });
  const board = await setup(page, async (route) => {
    await waiting;
    await route.fulfill({ json: { text: answer } });
  });
  await configure(page);
  await board.start();
  board.end();
  await expect(page.getByRole('button', { name: 'Cancel summary' })).toBeVisible();
  await board.start();
  release();
  await expect(page.getByRole('button', { name: 'Cancel summary' })).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Transcript text' })).toHaveText(
    'We agreed to meet tomorrow.',
  );
  await page.getByRole('button', { name: 'Open summaries' }).click();
  await expect(page.getByRole('dialog')).toContainText(answer);
});

test('missing key opens setup; summary replaces main text, keeps original and saves without opening drawer', async ({
  page,
}) => {
  let calls = 0;
  const board = await setup(page, async (route) => {
    calls++;
    expect(route.request().postDataJSON()).toMatchObject({
      text: finalWords,
      key,
      provider: 'deepseek',
      consent: true,
    });
    await route.fulfill({ json: { text: answer } });
  });
  await board.start();
  board.end();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toContainText(
    'Bring the prototype',
  );
  expect(calls).toBe(0);
  await page.getByRole('button', { name: 'Summarize', exact: true }).click();
  await expect(page.getByLabel('Summary API key', { exact: true })).toBeVisible();
  await saveSettings(page);
  await expect(page.getByRole('region', { name: 'Transcript text' })).toHaveText(answer);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: 'Show original transcript' }).click();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toHaveText(finalWords);
  await page.getByRole('button', { name: 'Back to summarized' }).click();
  await page.getByRole('button', { name: 'Open summaries' }).click();
  await expect(page.getByRole('dialog')).toContainText(answer);
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Delete entry' }).click();
  await expect(page.getByRole('dialog')).not.toContainText(answer);
  expect(calls).toBe(1);
  expect(
    await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage)),
  ).not.toContain(key);
});

test('BOOT stop automatically summarizes once, only after final recognition', async ({ page }) => {
  const texts: string[] = [];
  const board = await setup(page, async (route) => {
    texts.push(route.request().postDataJSON().text);
    await route.fulfill({ json: { text: answer } });
  });
  await configure(page);
  expect(texts).toEqual([]);
  await board.start();
  board.end();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toHaveText(answer);
  expect(texts).toEqual([finalWords]);
  expect(board.stops).toEqual([]);
});

test('Stop & summarize stops hardware first; cancellation ignores a late response', async ({
  page,
}) => {
  let release!: () => void;
  const waiting = new Promise<void>((resolve) => {
    release = resolve;
  });
  let called = false;
  const board = await setup(page, async (route) => {
    called = true;
    expect(route.request().postDataJSON().text).toBe(finalWords);
    await waiting;
    await route.fulfill({ json: { text: answer } }).catch(() => {});
  });
  await configure(page);
  await board.start();
  await page.getByRole('button', { name: 'Stop & summarize' }).click();
  await expect.poll(() => called).toBe(true);
  expect(board.stops).toEqual(['STOP r1']);
  await page.getByRole('button', { name: 'Cancel summary' }).click();
  release();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toHaveText(finalWords);
  await page.getByRole('button', { name: 'Open summaries' }).click();
  await expect(page.getByRole('dialog')).not.toContainText(answer);
});

test('deleting the source during a summary prevents resurrection', async ({ page }) => {
  let release!: () => void;
  const waiting = new Promise<void>((resolve) => {
    release = resolve;
  });
  const board = await setup(page, async (route) => {
    await waiting;
    await route.fulfill({ json: { text: answer } }).catch(() => {});
  });
  await configure(page);
  await board.start();
  board.end();
  await expect(page.getByRole('button', { name: 'Cancel summary' })).toBeVisible();
  await page.getByRole('button', { name: 'Open conversations' }).click();
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Delete entry' }).click();
  release();
  await expect(page.getByRole('dialog')).not.toContainText(finalWords);
  await page.getByRole('button', { name: 'Close dialog' }).click();
  await page.getByRole('button', { name: 'Open summaries' }).click();
  await expect(page.getByRole('dialog')).not.toContainText(answer);
});
