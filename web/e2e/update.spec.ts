import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

test('a real waiting service worker offers Update app and preserves IndexedDB history', async ({
  page,
}) => {
  // Separate origin with controllable sw.js bytes models a deployment while the
  // installed old page remains open. No browser mocks for the worker lifecycle.
  let revision = 1;
  const server = createServer(async (request, response) => {
    try {
      const pathname = new URL(request.url!, 'http://localhost').pathname;
      const file = pathname === '/' ? 'index.html' : pathname.slice(1);
      const root = path.resolve('dist');
      const resolved = path.resolve(root, file);
      if (!resolved.startsWith(root + path.sep)) {
        response.writeHead(404).end();
        return;
      }
      let bytes = await readFile(resolved);
      if (file === 'sw.js')
        bytes = Buffer.concat([bytes, Buffer.from(`\n// test deployment ${revision}\n`)]);
      const type = file.endsWith('.js')
        ? 'application/javascript'
        : file.endsWith('.html')
          ? 'text/html'
          : file.endsWith('.css')
            ? 'text/css'
            : file.endsWith('.svg')
              ? 'image/svg+xml'
              : 'application/json';
      response.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' }).end(bytes);
    } catch {
      response.writeHead(404).end();
    }
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const address = server.address() as { port: number };
    await page.goto(`http://127.0.0.1:${address.port}`);
    await page.evaluate(() => navigator.serviceWorker.ready.then(() => {}));
    await page.reload();
    await page.waitForFunction(() => !!navigator.serviceWorker.controller);
    await page.evaluate(
      () =>
        new Promise<void>((resolve, reject) => {
          const open = indexedDB.open('glyph-voice-web', 1);
          open.onsuccess = () => {
            const db = open.result;
            const tx = db.transaction('conversations', 'readwrite');
            tx.objectStore('conversations').put({
              id: 'saved-before-update',
              text: 'Keep my existing words.',
              status: 'complete',
              created: Date.now(),
            });
            tx.oncomplete = () => {
              db.close();
              resolve();
            };
            tx.onerror = () => reject(tx.error);
          };
          open.onerror = () => reject(open.error);
        }),
    );
    revision++;
    await page.evaluate(async () => {
      await (await navigator.serviceWorker.ready).update();
    });
    await expect(page.getByRole('button', { name: 'Update app' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Transcript text' })).toBeVisible();
    await Promise.all([
      page.waitForEvent('load'),
      page.getByRole('button', { name: 'Update app' }).click(),
    ]);
    await expect(page.getByRole('button', { name: 'Update app' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Open conversations' }).click();
    await expect(page.getByRole('dialog')).toContainText('Keep my existing words.');
  } finally {
    await page.goto('about:blank');
    await new Promise<void>((resolve) => {
      server.close(() => resolve());
      server.closeAllConnections();
    });
  }
});
