import { expect, test } from '@playwright/test';
import type { Page, WebSocketRoute } from '@playwright/test';

const metadata = JSON.stringify({
  type: 'start',
  version: 1,
  id: 'r1',
  sampleRate: 16000,
  channels: 1,
  encoding: 'pcm_s16le',
  control: 'stop-v1',
});
async function configure(page: Page) {
  await page.getByRole('button', { name: 'Connect Glyph' }).click();
  await page.getByRole('button', { name: 'Enter an existing Glyph IP' }).click();
  await page.getByRole('button', { name: 'Cloud provider', exact: true }).click();
  await page
    .getByLabel('Full HTTPS transcription URL')
    .fill('https://speech.example/transcriptions');
  await page.getByLabel('Speech model ID').fill('test-model');
  await page.getByLabel('API key (optional, this tab only)').fill('test-key-not-real');
  await page.getByRole('checkbox', { name: /I choose cloud/ }).check();
  await page.getByRole('button', { name: 'Use cloud speech' }).click();
  await page.getByLabel('Glyph address').fill('192.168.4.2:8080');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
}
test('responsive empty screen, accessible drawers, and no default cloud transmission', async ({
  page,
}) => {
  const external: string[] = [];
  page.on('request', (request) => {
    if (request.url().startsWith('https://')) external.push(request.url());
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Voicing your thoughts.' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Copy', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Open conversations' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(page.getByRole('button', { name: 'Open conversations' })).toBeFocused();
  await page.getByRole('button', { name: 'Open summaries' }).click();
  await expect(page.getByRole('button', { name: /Speech recognition/ })).toBeVisible();
  expect(external).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('real browser transport → mocked cloud → saved text, reload persistence and deletion', async ({
  page,
}) => {
  let socket!: WebSocketRoute;
  await page.routeWebSocket('ws://192.168.4.2:8080/audio', (ws) => {
    socket = ws;
  });
  await page.route('https://speech.example/transcriptions', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' },
      body: '{"text":"Here is a real pipeline test."}',
    }),
  );
  await page.goto('/');
  await configure(page);
  await expect(page.getByText('Glyph connected', { exact: true })).toBeVisible();
  socket.send(metadata);
  socket.send(Buffer.alloc(3200));
  socket.onMessage((message) => {
    expect(message).toBe('STOP r1');
    socket.send('{"type":"end","id":"r1","bytes":3200}');
  });
  await page.getByRole('button', { name: 'Stop recording' }).click();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toContainText(
    'Here is a real pipeline test.',
  );
  await page.getByRole('button', { name: 'Open conversations' }).click();
  await expect(page.getByRole('dialog')).toContainText('Here is a real pipeline test.');
  await page.reload();
  await page.getByRole('button', { name: 'Open conversations' }).click();
  await page.getByRole('button', { name: /Here is a real pipeline test/ }).click();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toContainText(
    'Here is a real pipeline test.',
  );
  await page.getByRole('button', { name: 'Open conversations' }).click();
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Delete entry' }).click();
  await expect(page.getByRole('dialog')).toContainText('Your thoughts, collected here.');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('region', { name: 'Transcript text' })).not.toContainText(
    'Here is a real pipeline test.',
  );
  await page.getByRole('button', { name: 'Connect Glyph' }).click(); // Reload erased the key/config.
  await page.getByRole('button', { name: 'Enter an existing Glyph IP' }).click();
  await page.getByRole('button', { name: 'Cloud provider', exact: true }).click();
  await expect(page.getByLabel('API key (optional, this tab only)')).toHaveValue('');
});
test('disconnect preserves partial text and marks history interrupted', async ({ page }) => {
  let socket!: WebSocketRoute;
  await page.routeWebSocket('ws://192.168.4.2:8080/audio', (ws) => {
    socket = ws;
  });
  await page.route('https://speech.example/transcriptions', (route) =>
    route.fulfill({
      contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' },
      body: '{"text":"Keep these words."}',
    }),
  );
  await page.goto('/');
  await configure(page);
  await expect(page.getByText('Glyph connected', { exact: true })).toBeVisible();
  socket.send(metadata);
  for (let i = 0; i < 150; i++) socket.send(Buffer.alloc(3200));
  await expect(page.getByRole('region', { name: 'Transcript text' })).toContainText(
    'Keep these words.',
  );
  socket.close();
  await expect(page.getByRole('region', { name: 'Transcript text' })).toContainText(
    'Keep these words.',
  );
  await page.getByRole('button', { name: 'Open conversations' }).click();
  await expect(page.getByRole('dialog')).toContainText('interrupted');
});

test('leaving foreground requests STOP, disconnects and does not upload pending audio', async ({
  page,
}) => {
  let socket!: WebSocketRoute;
  const commands: string[] = [],
    uploads: string[] = [];
  await page.routeWebSocket('ws://192.168.4.2:8080/audio', (ws) => {
    socket = ws;
    ws.onMessage((message) => commands.push(String(message)));
  });
  page.on('request', (request) => {
    if (request.url().startsWith('https://speech.example')) uploads.push(request.url());
  });
  await page.goto('/');
  await configure(page);
  await expect(page.getByText('Glyph connected', { exact: true })).toBeVisible();
  socket.send(metadata);
  socket.send(Buffer.alloc(3200));
  await expect(page.getByRole('button', { name: 'Stop recording' })).toBeVisible();
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(page.getByText('Glyph disconnected', { exact: true })).toBeVisible();
  await expect(page.getByRole('status')).toContainText('Page left the foreground');
  await expect.poll(() => commands).toEqual(['STOP r1']);
  expect(uploads).toEqual([]);
});

test('installed app shell reloads offline without claiming offline speech', async ({
  page,
  context,
}) => {
  await page.goto('/');
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.reload();
  await page.waitForFunction(() => !!navigator.serviceWorker.controller);
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Voicing your thoughts.' })).toBeVisible();
  await expect(
    page.getByText('Local processing runs on your server, not this browser.', { exact: false }),
  ).toBeVisible();
  await context.setOffline(false);
});
