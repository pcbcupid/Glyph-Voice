import { registerSW } from 'virtual:pwa-register';

let state = { ready: false, error: '' };
const listeners = new Set<() => void>();
function update(value: Partial<typeof state>) {
  state = { ...state, ...value };
  for (const listener of listeners) listener();
}
export const updates = {
  snapshot: () => state,
  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
  apply: async () => {},
};

/** Registered once, outside React StrictMode. Never reload a live session. */
export function startUpdates(canReload: () => boolean) {
  let registration: ServiceWorkerRegistration | undefined;
  let reloadPending = false;
  let lastCheck = 0;
  const apply = registerSW({
    immediate: true,
    onNeedRefresh: () => update({ ready: true }),
    onNeedReload: () => {
      // Another tab can activate the worker. Preserve this tab's live work.
      reloadPending = true;
      update({ ready: true });
      if (canReload()) window.location.reload();
    },
    onRegisteredSW: (_url, value) => {
      registration = value;
      if (value?.waiting) update({ ready: true });
    },
    onRegisterError: () => {
      /* App remains usable without offline installation. */
    },
  });
  updates.apply = async () => {
    if (!canReload()) return;
    if (reloadPending) {
      window.location.reload();
      return;
    }
    try {
      await apply(true);
    } catch {
      update({ error: 'Could not update. Reconnect to the server and try again.' });
    }
  };
  const check = () => {
    if (document.hidden || !navigator.onLine || Date.now() - lastCheck < 60_000) return;
    lastCheck = Date.now();
    // Checks install an update but never activate/reload without the user.
    void registration?.update().catch(() => {});
  };
  document.addEventListener('visibilitychange', check);
  window.addEventListener('focus', check);
  const timer = setInterval(check, 60_000);
  return () => {
    clearInterval(timer);
    document.removeEventListener('visibilitychange', check);
    window.removeEventListener('focus', check);
  };
}
