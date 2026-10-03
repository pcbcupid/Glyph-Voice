import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { registerSW } from 'virtual:pwa-register';
import { startUpdates, updates } from '../src/updates';

vi.mock('virtual:pwa-register', () => ({ registerSW: vi.fn() }));
let allowed = false;
let stop: () => void;
const reload = vi.fn();
const activate = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  allowed = false;
  vi.useFakeTimers();
  vi.clearAllMocks();
  vi.stubGlobal('window', Object.assign(new EventTarget(), { location: { reload } }));
  vi.stubGlobal('document', Object.assign(new EventTarget(), { hidden: false }));
  vi.stubGlobal('navigator', { onLine: true });
  vi.mocked(registerSW).mockReturnValue(activate);
  stop = startUpdates(() => allowed);
});
afterEach(() => {
  stop();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

it('announces a waiting version and refuses to activate during live work', async () => {
  const options = vi.mocked(registerSW).mock.calls.at(-1)![0]!;
  options.onNeedRefresh!();
  expect(updates.snapshot().ready).toBe(true);
  await updates.apply();
  expect(activate).not.toHaveBeenCalled();
  allowed = true;
  await updates.apply();
  expect(activate).toHaveBeenCalledWith(true);
});
it('does not reload live work when another tab activates an update', async () => {
  const options = vi.mocked(registerSW).mock.calls.at(-1)![0]!;
  options.onNeedReload!();
  expect(reload).not.toHaveBeenCalled();
  expect(updates.snapshot().ready).toBe(true);
  allowed = true;
  await updates.apply();
  expect(reload).toHaveBeenCalledOnce();
});
it('checks for updates when visible without activating them', async () => {
  const check = vi.fn().mockResolvedValue(undefined);
  vi.mocked(registerSW).mock.calls.at(-1)![0]!.onRegisteredSW!('/sw.js', {
    update: check,
  } as unknown as ServiceWorkerRegistration);
  await vi.advanceTimersByTimeAsync(60_000);
  expect(check).toHaveBeenCalledOnce();
  expect(activate).not.toHaveBeenCalled();
  expect(reload).not.toHaveBeenCalled();
});
