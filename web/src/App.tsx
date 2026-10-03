import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { FormEvent } from 'react';
import type { Runtime } from './Runtime';
import type { Conversation, Summary } from './core/types';
import { Icon } from './ui/Icon';
import { Modal } from './ui/Modal';
import { LocalSettings } from './ui/LocalSettings';
import type { LocalConfig } from './speech/LocalRecognizer';
import { localEndpoint } from './core/protocol';
import { SummarySettings } from './ui/SummarySettings';
import { UpdateNotice } from './ui/UpdateNotice';
import { SummaryStatus } from './ui/SummaryStatus';

type Panel =
  'conversations' | 'summaries' | 'connect' | 'speech' | 'about' | 'summary-settings' | null;
export function App({ runtime }: { runtime: Runtime }) {
  const state = useSyncExternalStore(runtime.subscribe, runtime.getSnapshot);
  const [panel, setPanel] = useState<Panel>(null);
  const [selected, setSelected] = useState<Conversation | Summary | null>(null);
  const [original, setOriginal] = useState(false);
  const [deletedCurrent, setDeletedCurrent] = useState('');
  const [message, setMessage] = useState('');
  const [address, setAddress] = useState(() => {
    try {
      const saved = localStorage.getItem('glyph.lastAddress') ?? '';
      if (saved) localEndpoint(saved, 'http:');
      return saved;
    } catch {
      return '';
    } // An optional preference must not block connection.
  });
  const pendingAddress = useRef('');
  const [awake, setAwake] = useState(true);
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
    setMessage('');
  }, [state.summaryBusy, state.session.conversation?.id]);

  useEffect(() => {
    if (state.summaryNeedsSetup) setPanel('summary-settings');
  }, [state.summaryNeedsSetup]);
  useEffect(() => {
    const result = state.summaryResult;
    if (
      result &&
      !busy &&
      ((!selected && state.session.conversation?.id === result.sourceId) ||
        selected?.id === result.sourceId ||
        summary?.sourceId === result.sourceId)
    ) {
      setSelected(result);
      setOriginal(false);
      follow.current = true;
    }
  }, [state.summaryResult]);

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
    if (panel === 'summary-settings') runtime.dismissSummarySetup();
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
  const title =
    state.summaryBusy && !busy
      ? 'Summarizing…'
      : viewingSummary
        ? 'Summarized'
        : original
          ? 'Original transcript'
          : 'Your words';
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
            {connected && <small> · {address}</small>}
          </div>
          <button
            className="quiet"
            disabled={state.loading}
            onClick={() => (connected ? runtime.disconnect() : setPanel('connect'))}
          >
            {connected ? 'Disconnect' : 'Connect Glyph'}
            <Icon name="arrow" />
          </button>
        </section>
        <section
          className={`transcript-card ${busy ? 'active' : ''} ${state.summaryBusy ? 'summarizing' : ''}`}
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
                <h3>{busy ? 'Receiving your words…' : 'Make room for your next thought.'}</h3>
                {busy ? (
                  <p>
                    Audio is received from the start. First words appear when recognition is ready.
                  </p>
                ) : (
                  <p>
                    Connect your Glyph. Click BOOT to speak,
                    <br />
                    then click again when you’re done.
                  </p>
                )}
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
        <SummaryStatus
          busy={state.summaryBusy}
          message={state.summaryMessage}
          error={state.summaryError}
          settings={() => setPanel('summary-settings')}
        />
        <div className="actions">
          <button className="secondary" disabled={!text} onClick={() => void copy()}>
            <Icon name="copy" />
            Copy
          </button>
          {state.session.phase === 'receiving' && (
            <button className="primary" disabled={state.stopping} onClick={() => runtime.stop()}>
              {state.stopping ? 'Waiting for Glyph…' : 'Stop recording'}
              <Icon name="wave" />
            </button>
          )}
          {state.summaryBusy ? (
            <button className="secondary" onClick={() => runtime.cancelSummary()}>
              Cancel summary
            </button>
          ) : (
            <button
              className="primary"
              disabled={(!text && !busy) || state.session.phase === 'processing'}
              onClick={() => {
                setMessage('');
                runtime.requestSummary(
                  busy
                    ? undefined
                    : summary
                      ? {
                          id: summary.sourceId,
                          text: summary.source,
                          created: summary.created,
                          status: 'complete',
                        }
                      : ((selected as Conversation | undefined) ?? undefined),
                );
              }}
            >
              <Icon name="summary" />
              {state.session.phase === 'receiving' ? 'Stop & summarize' : 'Summarize'}
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
          {message ||
            state.storageError ||
            (state.session.phase === 'receiving' && !state.stopping
              ? state.session.message
              : state.notice || state.session.message)}
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
        <UpdateNotice blocked={connected || busy || state.summaryBusy || state.loading} />
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
                    : 'English summaries appear here after you connect your API and summarize.'}
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
                <button
                  className="drawer-action"
                  disabled={state.summaryBusy}
                  onClick={() => setPanel('summary-settings')}
                >
                  <Icon name="summary" />
                  <span>
                    Connect your API
                    <small>
                      {state.summaryConfigured
                        ? 'Configured for this tab'
                        : 'DeepSeek or OpenAI · text summaries'}
                    </small>
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
      {panel === 'connect' && (
        <Modal title="Connect your Glyph" close={close}>
          <p className="connection-intro">Your board. Your IP. No pairing.</p>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              attempt(() => {
                localEndpoint(
                  address,
                  !state.configured || state.serverBridge ? 'http:' : location.protocol,
                );
                try {
                  localStorage.setItem('glyph.lastAddress', address.trim());
                } catch {
                  /* Remembering the IP is optional; no credentials are stored. */
                }
                if (!state.configured) {
                  pendingAddress.current = address.trim();
                  setPanel('speech');
                  return;
                }
                runtime.connect(address);
                close();
              });
            }}
          >
            <label>
              Glyph address
              <input
                autoFocus
                inputMode="url"
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                maxLength={21}
                aria-describedby="glyph-address-help"
                placeholder="192.168.43.42:8080"
                value={address}
                onChange={(e) => setAddress(e.target.value)}
                required
              />
            </label>
            <p id="glyph-address-help" className="connection-hint">
              Copy the [ready] IP from Serial Monitor (115200 baud), optionally with :8080. This
              browser remembers your last entry; update it if the board’s IP changes.
            </p>
            <p role="alert">{message}</p>
            <button className="primary" type="submit">
              {state.configured ? 'Connect' : 'Next: speech recognition'}
            </button>
          </form>
          <ol className="connection-steps">
            <li>
              <strong>Same Wi-Fi</strong>
              <span>Join the network your Glyph uses, or the phone hotspot hosting it.</span>
            </li>
            <li>
              <strong>Your board’s address</strong>
              <span>
                Use its recording IP, not the setup-page IP or another participant’s board.
              </span>
            </li>
            <li>
              <strong>Connect, then speak</strong>
              <span>
                Wait for “Glyph connected”, then click BOOT once to start and again to stop.
              </span>
            </li>
          </ol>
          <p className="callout">
            One board, one app at a time. Disconnect the Android app or any other browser using this
            Glyph. Your local model runs on the computer hosting your server—not on the board.
          </p>
          <details className="callout">
            <summary>First-time Wi-Fi setup / workshop</summary>
            <ol>
              <li>
                Open your board’s write-capable USB serial monitor at 115200 baud. Match the GLYPH suffix printed there to
                your board—not another participant’s.
              </li>
              <li>
                Reset with BOOT released, then tap BOOT during the first 3-second countdown.
                Type a board hotspot label and password in serial when prompted (transport-r11).
                Join the printed GLYPH-name-suffix network using that password, then open
                http://192.168.4.1 to enter your router’s 2.4 GHz Wi-Fi details. First-time boards
                enter setup automatically. Older firmware may use GLYPH-Setup / glyphvoice instead.
              </li>
              <li>
                Return to that Wi-Fi and copy the new address from the serial monitor here. The
                setup address 192.168.4.1 is not normally the recording address.
              </li>
              <li>
                Disconnect the Android app first. Use one board and one local model server per
                participant/computer. Shared Wi-Fi must allow devices to reach each other.
              </li>
            </ol>
            <p>
              Already configured? Just use its current IP. No Bluetooth permission, discovery or
              pairing PIN is needed. Leave BOOT released during startup to use the saved Wi-Fi.
            </p>
            <p>
              Label each board with its GLYPH suffix. IPs can change after reconnecting; use the
              latest serial output. Only connect on a trusted workshop network.
            </p>
          </details>
          <details className="callout">
            <summary>Connection troubleshooting</summary>
            <p>
              Use your own board’s latest IP, not 192.168.4.1 from Wi-Fi setup. Keep the board near
              the router/hotspot. Guest Wi-Fi or client isolation can block communication even when
              both devices are on the same network.
            </p>
            <p>
              {state.serverBridge
                ? 'Your local server must reach the board, and the IP must match its --glyph-host launch option.'
                : 'For a workshop on this computer, use the localhost page opened by start-web. An HTTPS page requires the local server’s --glyph-host bridge to reach a plain WebSocket board.'}
            </p>
          </details>
        </Modal>
      )}
      {panel === 'speech' && (
        <Modal title="Speech recognition" close={close}>
          <SpeechSettings
            configured={state.configured}
            disabled={connected || busy}
            save={(config) => {
              runtime.configure(config);
              if (pendingAddress.current && location.protocol === 'http:')
                attempt(() => {
                  runtime.connect(pendingAddress.current);
                  pendingAddress.current = '';
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
              if (pendingAddress.current && (config.bridge || location.protocol === 'http:'))
                attempt(() => {
                  runtime.connect(pendingAddress.current);
                  pendingAddress.current = '';
                  close();
                });
              else setPanel('connect');
            }}
          />
        </Modal>
      )}
      {panel === 'summary-settings' && (
        <Modal title="Connect your API" close={close}>
          <SummarySettings
            connection={runtime.getSummaryConnection()}
            configured={state.summaryConfigured}
            save={(config) => {
              runtime.configureSummary(config);
              close();
            }}
            forget={() => {
              runtime.forgetSummary();
              close();
            }}
          />
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
              Phone-only browser inference is not ported yet. AI summaries send text to your chosen
              provider through the local server. The optional local bridge supports same-origin
              audio behind your HTTPS reverse proxy.
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
