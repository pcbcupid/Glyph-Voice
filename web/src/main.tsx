import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { Runtime } from './Runtime';
import './style.css';

const runtime = new Runtime();
void runtime.init();
createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App runtime={runtime} />
  </React.StrictMode>,
);
if (import.meta.hot) import.meta.hot.dispose(() => runtime.destroy());
