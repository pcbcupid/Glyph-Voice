import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { FormEvent } from 'react';
import type { Runtime } from './Runtime';
import type { Conversation, Summary } from './core/types';
import { Icon } from './ui/Icon';
import { Modal } from './ui/Modal';
import { GlyphSetup } from './ui/GlyphSetup';
import { LocalSettings } from './ui/LocalSettings';
import type { LocalConfig } from './speech/LocalRecognizer';

type Panel =
  | 'conversations'
  | 'summaries'
  | 'connect'
  | 'speech'
  | 'about'
  | 'summary-info'
  | 'glyph-setup'
  | null;
export function App({ runtime }: { runtime: Runtime }) {
  const state = useSyncExternalStore(runtime.subscribe, runtime.getSnapshot);
  const [panel, setPanel] = useState<Panel>(null);
  const [selected, setSelected] = useState<Conversation | Summary | null>(null);
  const [original, setOriginal] = useState(false);
  const [deletedCurrent, setDeletedCurrent] = useState('');
  const [message, setMessage] = useState('');
  const [address, setAddress] = useState('');
  const bleAddress = useRef('');
  const [awake, setAwake] = useState(false);
  const [wakeStatus, setWakeStatus] = useState('');
  const scroll = useRef<HTMLDivElement>(null);
  const follow = useRef(true);
  const busy = state.session.phase === 'receiving' || state.session.phase === 'processing';
  const connected = state.connection !== 'disconnected';
  const summary = selected && 'source' in selected ? selected : null;
  const text =
    summary && original
      ? summary.source
      : (selected?.text ??
        (state.session.conversation?.id === deletedCurrent
          ? ''
          : (state.session.conversation?.text ?? '')));
  const viewingSummary = !!summary && !original;

  useEffect(() => {
    if (state.session.phase === 'receiving') {
      setSelected(null);
      setOriginal(false);
      setPanel(null);
      follow.current = true;
    }
  }, [state.session.conversation?.id]);
  useEffect(() => {
    const element = scroll.current;
    if (element && follow.current && !window.getSelection()?.toString())
      element.scrollTop = element.scrollHeight;
  }, [text]);
  useEffect(() => {
    const leave = () => {
      if (document.hidden && runtime.getSnapshot().connection !== 'disconnected')
        runtime.disconnect(
          'Page left the foreground. Recording interrupted; saved words kept. Reconnect to start again.',
        );
    };
    const pagehide = () => {
      if (runtime.getSnapshot().connection !== 'disconnected')
        runtime.disconnect('Page closed. Recording interrupted.');
    };
    document.addEventListener('visibilitychange', leave);
    window.addEventListener('pagehide', pagehide);
    return () => {
      document.removeEventListener('visibilitychange', leave);
      window.removeEventListener('pagehide', pagehide);
    };
  }, [runtime]);
  useEffect(() => {
    let disposed = false;
    let lock: WakeLockSentinel | undefined;
    setWakeStatus('');
    if (awake && connected) {
      if (!window.isSecureContext || !('wakeLock' in navigator))
        setWakeStatus('Screen wake lock unavailable here. Keep the screen awake manually.');
      else
        void navigator.wakeLock
          .request('screen')
          .then((value) => {
            if (disposed) {
              void value.release();
              return;
            }
            lock = value;
            setWakeStatus('Screen wake lock active');
            value.addEventListener('release', () => {
              if (!disposed) setWakeStatus('Screen wake lock released. Keep this page visible.');
            });
          })
          .catch(() => {
            if (!disposed)
              setWakeStatus('Screen wake lock was not granted. Keep the screen awake manually.');
          });
    }
    return () => {
      disposed = true;
      void lock?.release();
    };
  }, [awake, connected]);
  const close = () => {
    setPanel(null);
    setMessage('');
  };
  const attempt = (action: () => void) => {
    try {
      action();
      setMessage('');
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Could not complete that action.');
    }
  };
  async function copy() {
    try {
      if (!navigator.clipboard) throw new Error();
      await navigator.clipboard.writeText(text);
      setMessage('Copied to clipboard.');
    } catch {
      // Clipboard API needs HTTPS; a LAN HTTP prototype still supports selection/copy.
      if (scroll.current) {
        const range = document.createRange();
        range.selectNodeContents(scroll.current);
        const selection = window.getSelection();
        selection?.removeAllRanges();
        selection?.addRange(range);
      }
      setMessage(
        'Automatic clipboard access is unavailable. Text selected—use your browser’s Copy action.',
      );
    }
  }
  async function remove(row: Conversation | Summary, isSummary: boolean) {
    if (!window.confirm(`Delete this ${isSummary ? 'summary' : 'conversation'} from this browser?`))
      return;
    try {
      await runtime.delete(row.id, isSummary);
      if (selected?.id === row.id) {
        setSelected(null);
        setOriginal(false);
      }
      if (!isSummary && row.id === state.session.conversation?.id) setDeletedCurrent(row.id);
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Could not delete this entry.');
    }
  }
  const title = viewingSummary ? 'Summarized' : original ? 'Original transcript' : 'Your words';
  return (
    <>
      <header className="topbar">
        <button
          className="icon-button"
          aria-label="Open conversations"
          onClick={() => setPanel('conversations')}
        >
          <Icon name="history" />
        </button>
        <a
          className="wordmark"
          href="#"
          onClick={(event) => {
            event.preventDefault();
            if (!busy) {
              setSelected(null);
              setOriginal(false);
            }
          }}
        >
          <span className="brand-mark">
            <Icon name="wave" />
          </span>
          <span>
            PCBCUPID <strong>GLYPH VOICE</strong>
          </span>
        </a>
        <button
          className="icon-button"
          aria-label="Open summaries"
          onClick={() => setPanel('summaries')}
        >
          <Icon name="summary" />
        </button>
      </header>
      <main>
        <div className="intro">
          <div>
            <p className="eyebrow">A little space to think out loud</p>
            <h1>
              Voicing your thoughts<span>.</span>
            </h1>
          </div>
          <span className="version">WEB PREVIEW</span>
        </div>
        <section className="connection-bar" aria-label="Glyph connection">
          <div className="status">
            <span className={`dot ${state.connection === 'connected' ? 'online' : ''}`} />
            <span>
              {state.connection === 'connected'
                ? 'Glyph connected'
                : state.connection === 'disconnected'
                  ? 'Glyph disconnected'
                  : state.connection === 'connecting'
                    ? 'Connecting…'
                    : 'Reconnecting…'}
            </span>
          </div>
          <button
            className="quiet"
            disabled={state.loading}
            onClick={() => (connected ? runtime.disconnect() : setPanel('glyph-setup'))}
          >
            {connected ? 'Disconnect' : 'Connect Glyph'}
            <Icon name="arrow" />
          </button>
        </section>
        <section
          className={`transcript-card ${busy ? 'active' : ''}`}
          aria-labelledby="transcript-title"
        >
          <div className="card-head">
            <h2 id="transcript-title">{title}</h2>
            <span className="mode">
              {busy
                ? state.session.phase === 'receiving'
                  ? 'RECEIVING AUDIO'
                  : 'PROCESSING'
                : state.configured
                  ? state.speechMode === 'local'
                    ? 'LOCAL SERVER · STREAMING'
                    : 'CLOUD · 15s CHUNKS'
                  : 'SPEECH NOT CONFIGURED'}
            </span>
          </div>
          <div
            className="transcript-scroll"
            ref={scroll}
            tabIndex={0}
            role="region"
            aria-label="Transcript text"
            onScroll={(event) => {
              const el = event.currentTarget;
              follow.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
            }}
          >
            {text ? (
              <p className="transcript">{text}</p>
            ) : (
              <div className="empty">
                <span className="wave-tile">
                  <Icon name="wave" />
                </span>
                <h3>Make room for your next thought.</h3>
                <p>
                  Connect your Glyph. Click BOOT to speak,
                  <br />
                  then click again when you’re done.
                </p>
                <small>
                  Choose a local model server or optional cloud speech.
                  <br />
                  Local processing runs on your server, not this browser.
                </small>
              </div>
            )}
          </div>
          <div className="card-foot">
            <span>
              {text
                ? `${text.trim().split(/\s+/).length} words`
                : 'Your transcript will appear here'}
              {state.session.bytes > 0 && !selected
                ? ` · ${(state.session.bytes / (state.session.sampleRate * 2)).toFixed(1)}s received`
                : ''}
            </span>
            <span>Saved in this browser</span>
          </div>
        </section>
        <div className="actions">
          <button className="secondary" disabled={!text} onClick={() => void copy()}>
            <Icon name="copy" />
            Copy
          </button>
          {state.session.phase === 'receiving' ? (
            <button className="primary" disabled={state.stopping} onClick={() => runtime.stop()}>
              {state.stopping ? 'Waiting for Glyph…' : 'Stop recording'}
              <Icon name="wave" />
            </button>
          ) : (
            <button
              className="primary"
              disabled={!text || busy}
              onClick={() => setPanel('summary-info')}
            >
              <Icon name="summary" />
              Summarize
            </button>
          )}
        </div>
        {summary && (
          <button
            className="text-button"
            onClick={() => {
              follow.current = false;
              setOriginal(!original);
            }}
          >
            {original ? 'Back to summarized' : 'Show original transcript'}
          </button>
        )}
        {selected && !summary && (
          <button className="text-button" onClick={() => setSelected(null)}>
            Back to current transcript
          </button>
        )}
        <p className="feedback" role="status">
          {message || state.storageError || state.notice || state.session.message}
        </p>
        <aside className="foreground-note">
          <span className="note-title">Keep this page open while recording.</span> Switching apps or
          locking your phone interrupts this web preview. There is no background-recording
          permission.
          <label className="check">
            <input type="checkbox" checked={awake} onChange={(e) => setAwake(e.target.checked)} />
            Keep screen awake when supported
          </label>
          {wakeStatus && <small>{wakeStatus}</small>}
        </aside>
        <footer>
          <span>GLYPH C6 → Wi-Fi → your browser</span>
          <button className="text-button" onClick={() => setPanel('about')}>
            About this preview
          </button>
        </footer>
      </main>
      {(panel === 'conversations' || panel === 'summaries') && (
        <Modal
          title={panel === 'conversations' ? 'Conversations' : 'Summaries'}
          side={panel === 'conversations' ? 'left' : 'right'}
          close={close}
        >
          <p className="muted">Private to this browser. Clearing site data removes this history.</p>
          <div className="history-list">
            {(panel === 'conversations' ? state.conversations : state.summaries).length === 0 ? (
              <div className="drawer-empty">
                <Icon name={panel === 'conversations' ? 'history' : 'summary'} />
                <p>
                  {panel === 'conversations'
                    ? 'Your thoughts, collected here.'
                    : 'A home for the essentials.'}
                </p>
                <small>
                  {panel === 'conversations'
                    ? 'Saved transcripts will appear after you speak.'
                    : 'AI summary generation is not connected in this first web slice.'}
                </small>
              </div>
            ) : (
              (panel === 'conversations' ? state.conversations : state.summaries).map((row) => (
                <article className="history-row" key={row.id}>
                  <button
                    disabled={busy}
                    onClick={() => {
                      setSelected(row);
                      setOriginal(false);
                      follow.current = false;
                      close();
                    }}
                  >
                    <strong>{row.text.slice(0, 90)}</strong>
                    <small>
                      {new Date(row.created).toLocaleString()}
                      {'status' in row ? ` · ${row.status}` : ''}
                    </small>
                  </button>
                  <button
                    className="icon-button"
                    aria-label="Delete entry"
                    onClick={() => void remove(row, panel === 'summaries')}
                  >
                    <Icon name="trash" />
                  </button>
                </article>
              ))
            )}
          </div>
          <div className="drawer-bottom">
            {panel === 'summaries' && (
              <>
                <button className="drawer-action" onClick={() => setPanel('speech')}>
                  <Icon name="settings" />
                  <span>
                    Speech recognition
                    <small>
                      {state.configured
                        ? state.speechMode === 'local'
                          ? 'Local server · ' + state.modelName
                          : 'Cloud · configured for this tab'
                        : 'Choose local model or cloud'}
                    </small>
                  </span>
                  <Icon name="arrow" />
                </button>
                <button className="drawer-action" onClick={() => setPanel('summary-info')}>
                  <Icon name="summary" />
                  <span>
                    Connect your API<small>Summary migration · coming next</small>
                  </span>
                  <Icon name="arrow" />
                </button>
              </>
            )}
            <button className="drawer-action" onClick={() => setPanel('about')}>
              <Icon name="wave" />
              <span>
                About Glyph Voice<small>What works in the web preview</small>
              </span>
              <Icon name="arrow" />
            </button>
          </div>
        </Modal>
      )}
      {panel === 'glyph-setup' && (
        <GlyphSetup
          close={close}
          manual={() => {
            bleAddress.current = '';
            setPanel(state.configured ? 'connect' : 'speech');
          }}
          connected={(endpoint) => {
            setAddress(endpoint);
            bleAddress.current = endpoint;
            if (location.protocol === 'https:' && !state.serverBridge) {
              close();
              setMessage(
                `Glyph joined Wi-Fi at ${endpoint}. Configure a local model server with --glyph-host to bridge HTTPS audio.`,
              );
            } else if (!state.configured) setPanel('speech');
            else
              attempt(() => {
                runtime.connect(endpoint);
                close();
              });
          }}
        />
      )}
      {panel === 'connect' && (
        <Modal title="Connect your Glyph" close={close}>
          <p>
            Use the same local network as the board. Browser UDP discovery is unavailable; enter the
            address printed in its serial monitor.
          </p>
          <p className="callout">
            {state.serverBridge
              ? 'Your self-hosted server bridges audio from the permitted board IP. It must be on the board’s network.'
              : 'Direct connection uses plain WebSocket on a trusted LAN. For HTTPS, configure local speech with the server’s --glyph-host bridge.'}
          </p>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              attempt(() => {
                runtime.connect(address);
                close();
              });
            }}
          >
            <label>
              Glyph address
              <input
                autoFocus
                inputMode="decimal"
                autoComplete="off"
                placeholder="192.168.43.42:8080"
                value={address}
                onChange={(e) => setAddress(e.target.value)}
                required
              />
            </label>
            <p role="alert">{message}</p>
            <button className="primary" type="submit">
              Connect
            </button>
          </form>
        </Modal>
      )}
      {panel === 'speech' && (
        <Modal title="Speech recognition" close={close}>
          <SpeechSettings
            configured={state.configured}
            disabled={connected || busy}
            save={(config) => {
              runtime.configure(config);
              if (bleAddress.current && location.protocol === 'http:')
                attempt(() => {
                  runtime.connect(bleAddress.current);
                  bleAddress.current = '';
                  close();
                });
              else setPanel('connect');
            }}
            forget={() => {
              runtime.forgetKey();
              close();
            }}
            saveLocal={(config) => {
              runtime.configureLocal(config);
              if (bleAddress.current && (config.bridge || location.protocol === 'http:'))
                attempt(() => {
                  runtime.connect(bleAddress.current);
                  bleAddress.current = '';
                  close();
                });
              else setPanel('connect');
            }}
          />
        </Modal>
      )}
      {panel === 'summary-info' && (
        <Modal title="AI summaries · next milestone" close={close}>
          <p>
            The Android app’s summary workflow is not wired into this first web slice yet. Your
            transcript remains available to copy and read.
          </p>
          <p>
            Provider CORS, browser credential handling, automatic summaries and cancel/delete races
            need a dedicated port. No text has been sent and no API key is requested here.
          </p>
          <button className="secondary" onClick={close}>
            Back to my words
          </button>
        </Modal>
      )}
      {panel === 'about' && (
        <Modal title="Glyph Voice, on the web" close={close}>
          <p>
            React + TypeScript preview. Receive Glyph PCM audio, transcribe on your self-hosted
            model server or an optional cloud endpoint, and keep text in this browser.
          </p>
          <ul>
            <li>
              Both modes send audio to the server you explicitly configure. Local mode uses your own
              model server, not a cloud STT provider.
            </li>
            <li>Keys live in this tab’s memory, not browser storage. Refreshing clears them.</li>
            <li>
              Phone-only browser inference and AI summaries are not ported yet. The optional local
              bridge supports same-origin audio behind your HTTPS reverse proxy.
            </li>
            <li>The installable app shell can load offline; cloud transcription cannot.</li>
            <li>History is not synced with the Android app. Browsers may evict site storage.</li>
          </ul>
          <p>
            No phone-microphone permission, accounts or analytics. Local mode requires the included
            Python backend.
          </p>
        </Modal>
      )}
    </>
  );
}

function SpeechSettings({
  configured,
  disabled,
  save,
  saveLocal,
  forget,
}: {
  configured: boolean;
  disabled: boolean;
  save: (config: { endpoint: string; model: string; key: string }) => void;
  saveLocal: (config: LocalConfig) => void;
  forget: () => void;
}) {
  const [endpoint, setEndpoint] = useState('');
  const [model, setModel] = useState('');
  const [key, setKey] = useState('');
  const [consent, setConsent] = useState(false);
  const [error, setError] = useState('');
  const [mode, setMode] = useState<'local' | 'cloud'>('local');
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!consent || disabled) return;
    try {
      save({ endpoint, model, key });
      setKey('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Check your settings.');
    }
  }
  return (
    <>
      <div className="speech-modes" role="group" aria-label="Speech processing location">
        <button aria-pressed={mode === 'local'} onClick={() => setMode('local')}>
          Local model
        </button>
        <button aria-pressed={mode === 'cloud'} onClick={() => setMode('cloud')}>
          Cloud provider
        </button>
      </div>
      {mode === 'local' ? (
        <LocalSettings disabled={disabled} save={saveLocal} />
      ) : (
        <form onSubmit={submit}>
          <p className="callout">
            Optional cloud mode sends 15-second WAV chunks to your chosen provider. This is chunked
            transcription, not word-by-word streaming.
          </p>
          <p>
            The endpoint must accept multipart audio transcription and allow browser CORS from this
            site. No proxy is provided. Internet and provider charges apply.
          </p>
          <fieldset disabled={disabled}>
            <label>
              Full HTTPS transcription URL
              <input
                type="url"
                autoComplete="off"
                value={endpoint}
                onChange={(e) => {
                  setEndpoint(e.target.value);
                  setKey('');
                }}
                placeholder="https://your-provider/v1/audio/transcriptions"
                required
              />
            </label>
            <label>
              Speech model ID
              <input
                autoComplete="off"
                value={model}
                onChange={(e) => setModel(e.target.value)}
                required
              />
            </label>
            <label>
              API key (optional, this tab only)
              <input
                type="password"
                autoComplete="off"
                spellCheck={false}
                value={key}
                onChange={(e) => setKey(e.target.value)}
              />
            </label>
            <label className="check">
              <input
                type="checkbox"
                checked={consent}
                onChange={(e) => setConsent(e.target.checked)}
                required
              />
              I choose cloud speech and understand that microphone audio is sent to this endpoint.
            </label>
            <button className="primary" disabled={!consent} type="submit">
              Use cloud speech
            </button>
          </fieldset>
          {disabled && <p>Disconnect Glyph before changing speech settings.</p>}
          <p role="alert">{error}</p>
          <small>
            Never enter a shared production key. JavaScript can access a key while this tab is open;
            browser memory is not Android Keystore.
          </small>
        </form>
      )}
      {configured && (
        <button className="text-button" type="button" onClick={forget}>
          Disconnect and clear this tab’s speech settings
        </button>
      )}
    </>
  );
}
