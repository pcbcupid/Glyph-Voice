import { useSyncExternalStore } from 'react';
import { updates } from '../updates';

export function UpdateNotice({ blocked }: { blocked: boolean }) {
  const state = useSyncExternalStore(updates.subscribe, updates.snapshot);
  if (!state.ready) return null;
  return (
    <aside className="update-notice" aria-label="App update">
      <strong>A new Glyph Voice version is ready.</strong>
      <p>
        {blocked
          ? 'Finish recording and summaries, then disconnect Glyph to update.'
          : 'History is kept. Updating reloads this tab; re-enter your API/server keys afterward. Finish work in other Glyph tabs first.'}
      </p>
      <button className="secondary" disabled={blocked} onClick={() => void updates.apply()}>
        Update app
      </button>
      {state.error && <p role="alert">{state.error}</p>}
    </aside>
  );
}
