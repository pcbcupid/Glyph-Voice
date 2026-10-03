import { expect, test } from '@playwright/test';
import type { WebSocketRoute } from '@playwright/test';

test('choose server model folder, load, receive live local words and finish without cloud', async ({
  page,
}) => {
  const token = 'test-token-not-real-1234567890',
    id = 'b'.repeat(32);
  const external: string[] = [],
    requests: string[] = [];
  let finishWarmup!: () => void;
  const warming = new Promise<void>((resolve) => {
    finishWarmup = resolve;
  });
  page.on('request', (request) => {
    if (request.url().startsWith('https://')) external.push(request.url());
  });
  await page.route('**/api/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url());
    expect(request.headers().authorization).toBe('Bearer ' + token);
    requests.push(url.pathname);
    let body: unknown;
    if (url.pathname === '/api/local')
      body = { model: null, modelId: null, active: false, glyphBridge: true };
    else if (url.pathname === '/api/folders')
      body =
        url.searchParams.get('path') === '.'
          ? {
              path: '.',
              parent: null,
              folders: [{ name: 'Parakeet', path: 'Parakeet' }],
              compatibleFiles: false,
            }
          : { path: 'Parakeet', parent: '.', folders: [], compatibleFiles: true };
    else if (url.pathname === '/api/model') {
      expect(request.postDataJSON()).toEqual({ folder: 'Parakeet' });
      await warming;
      body = { model: 'Parakeet', modelId: 'a'.repeat(32) };
    } else if (url.pathname === '/api/streams') body = { id };
    else if (url.pathname.endsWith('/audio'))
      body = {
        text: 'These words are local.',
        sequence: Number(request.headers()['x-audio-sequence']),
      };
    else body = { text: 'These words are local. Final result.' };
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
  });
  let socket!: WebSocketRoute;
  await page.routeWebSocket('ws://127.0.0.1:4173/api/glyph', (ws) => {
    socket = ws;
    ws.onMessage((message) => {
      if (String(message).startsWith('{')) {
        expect(JSON.parse(String(message))).toEqual({ token, address: '192.168.4.2:8080' });
        ws.send('{"proxy":"ready"}');
      } else {
        expect(message).toBe('STOP r1');
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
  await expect(page.getByRole('button', { name: 'Load this model locally' })).toBeDisabled();
  await page.getByRole('button', { name: /Parakeet/ }).click();
  await page.screenshot({
    path: test.info().outputPath('local-model-folder.png'),
    animations: 'disabled',
  });
  await page.getByRole('button', { name: 'Load this model locally' }).click();
  await expect(page.getByRole('dialog')).toContainText('Loading and warming up');
  expect(socket).toBeUndefined(); // No board connection or recording during cold initialization.
  finishWarmup();
  await expect(page.getByText('Glyph connected', { exact: true })).toBeVisible();
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
    'These words are local.',
  );
  await page.getByRole('button', { name: 'Stop recording' }).click();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toContainText(
    'Final result.',
  );
  expect(external).toEqual([]);
  expect(requests.some((p) => p.endsWith('/audio'))).toBe(true);
  expect(
    await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage)),
  ).not.toContain(token);
});
