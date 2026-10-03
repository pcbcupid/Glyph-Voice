import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { Runtime } from './Runtime';
import { startUpdates } from './updates';
import './style.css';

const runtime = new Runtime();
const stopUpdates = startUpdates(() => {
  const state = runtime.getSnapshot();
  return (
    state.connection === 'disconnected' &&
    !state.loading &&
    !state.summaryBusy &&
    !['receiving', 'processing'].includes(state.session.phase)
  );
});
void runtime.init();
createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App runtime={runtime} />
  </React.StrictMode>,
);
if (import.meta.hot)
  import.meta.hot.dispose(() => {
    stopUpdates();
    runtime.destroy();
  });
