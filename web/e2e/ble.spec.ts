import { expect, test } from '@playwright/test';

test('radar shows no invented devices and unsupported browsers get Wi-Fi fallback', async ({
  page,
}) => {
  await page.addInitScript(() => {
    delete (Navigator.prototype as unknown as { bluetooth?: unknown }).bluetooth;
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Connect Glyph' }).click();
  await expect(page.getByRole('heading', { name: 'Find your Glyph' })).toBeVisible();
  await expect(page.getByRole('img', { name: 'No Glyph selected' })).toBeVisible();
  await expect(page.locator('.radar-device')).toHaveCount(0);
  await expect(page.getByRole('dialog')).toContainText('does not support Web Bluetooth');
  await page.getByRole('button', { name: 'iPhone / Wi-Fi setup fallback' }).click();
  await expect(page.getByRole('link', { name: 'the local setup page' })).toHaveAttribute(
    'href',
    'http://192.168.4.1',
  );
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('mocked BLE picker → credentials → discovered IP, without network credential uploads', async ({
  page,
}) => {
  await page.addInitScript(() => {
    const packets: number[][] = [];
    (window as unknown as { blePackets: number[][] }).blePackets = packets;
    let state = 'ready';
    const status = {
      readValue: async () =>
        new DataView(
          new TextEncoder().encode(
            JSON.stringify({
              version: 1,
              id: 'AABBCCDDEEFF',
              state,
              ip: state === 'connected' ? '192.168.4.2' : '',
              port: 8080,
              path: '/audio',
            }),
          ).buffer,
        ),
    };
    const command = {
      writeValueWithResponse: async (bytes: Uint8Array) => {
        packets.push([...bytes]);
        if (bytes[0] === 3) state = 'connected';
      },
    };
    const server = {
      connected: false,
      connect: async () => {
        server.connected = true;
        return server;
      },
      disconnect: () => {
        server.connected = false;
      },
      getPrimaryService: async () => ({
        getCharacteristic: async (uuid: string) => (uuid.includes('f101-') ? status : command),
      }),
    };
    const device = Object.assign(new EventTarget(), {
      id: 'mock-board',
      name: 'GLYPH-DDEEFF',
      gatt: server,
    });
    Object.defineProperty(navigator, 'bluetooth', {
      configurable: true,
      value: { requestDevice: async () => device },
    });
  });
  const sentBodies: string[] = [];
  page.on('request', (request) => {
    if (request.postData()) sentBodies.push(request.postData()!);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Connect Glyph' }).click();
  await page.getByRole('button', { name: 'Find nearby Glyph' }).click();
  await expect(page.getByRole('img', { name: 'GLYPH-DDEEFF selected' })).toBeVisible();
  await page.getByLabel('Wi-Fi / hotspot name').fill('Lab');
  await page.getByLabel('Wi-Fi password', { exact: true }).fill('testpass');
  await page.getByRole('button', { name: 'Send settings & connect' }).click();
  await expect(page.getByRole('dialog')).toContainText('192.168.4.2:8080');
  expect(
    await page.evaluate(() => (window as unknown as { blePackets: number[][] }).blePackets),
  ).toEqual([[1, 13], [2, 0, 3, 8, 76, 97, 98, 116, 101, 115, 116, 112, 97, 115, 115], [3]]);
  expect(sentBodies).toEqual([]);
  expect(
    await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage)),
  ).not.toContain('testpass');
  await page.getByRole('button', { name: 'Continue to voice' }).click();
  await expect(page.getByRole('heading', { name: 'Speech recognition' })).toBeVisible();
  // Wi-Fi setup never opts the user into cloud audio on their behalf.
  await expect(page.getByRole('button', { name: 'Local model', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await expect(page.getByRole('checkbox', { name: /I trust this server/ })).not.toBeChecked();
});
