import { useEffect, useRef, useState } from 'react';
import { LocalApi } from '../speech/LocalRecognizer';
import type { FolderListing, LocalConfig, LocalInfo } from '../speech/LocalRecognizer';

export function LocalSettings({
  disabled,
  save,
}: {
  disabled: boolean;
  save: (config: LocalConfig) => void;
}) {
  const [url, setUrl] = useState(
    location.port === '5173' ? 'http://localhost:8765' : location.origin,
  );
  const [token, setToken] = useState('');
  const [folder, setFolder] = useState<FolderListing | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [consent, setConsent] = useState(false);
  const [bridge, setBridge] = useState(false);
  const api = useRef<LocalApi | null>(null);
  const generation = useRef(0);
  useEffect(
    () => () => {
      generation.current++;
    },
    [],
  );
  function reset() {
    generation.current++;
    api.current = null;
    setFolder(null);
    setError('');
    setBusy(false);
  }
  async function browse(path = '.', initial = false) {
    const version = ++generation.current;
    setBusy(true);
    setError('');
    try {
      const client = initial ? new LocalApi({ url, token }) : api.current;
      if (!client) throw new Error('Connect to your local server first.');
      if (initial) {
        const info = await client.request<LocalInfo>('local');
        if (version !== generation.current) return;
        setBridge(info.glyphBridge === true);
        api.current = client;
      }
      const value = await client.request<FolderListing>('folders?path=' + encodeURIComponent(path));
      if (!value || !Array.isArray(value.folders) || typeof value.path !== 'string')
        throw new Error('Invalid server folder listing.');
      if (version === generation.current) setFolder(value);
    } catch (e) {
      if (version === generation.current)
        setError(e instanceof Error ? e.message : 'Could not open model folders.');
    } finally {
      if (version === generation.current) setBusy(false);
    }
  }
  async function load() {
    const client = api.current;
    if (!client || !folder) return;
    const version = ++generation.current;
    setBusy(true);
    setError('');
    try {
      const info = await client.request<LocalInfo>(
        'model',
        'POST',
        { folder: folder.path },
        undefined,
        130000,
      );
      if (version !== generation.current) return;
      if (!info.modelId || !info.model) throw new Error('Server did not load a model.');
      save({ ...client.config, modelId: info.modelId, name: info.model, bridge });
      setToken('');
    } catch (e) {
      if (version === generation.current)
        setError(e instanceof Error ? e.message : 'Could not load this model.');
    } finally {
      if (version === generation.current) setBusy(false);
    }
  }
  return (
    <section aria-label="Self-hosted local model">
      <p className="callout">
        Parakeet runs on your own server/computer, not inside this browser. Audio travels to that
        machine; no cloud STT or API key is needed. For phone-only offline inference, keep using the
        Android app.
      </p>
      <p>
        Start the included Python server with <code>--models-dir</code> pointing to your model
        collection. This picker browses folders on that server—not files on your phone—and does not
        upload model files.
      </p>
      <fieldset disabled={disabled || busy}>
        <label>
          Local server URL
          <input
            type="url"
            value={url}
            autoComplete="off"
            onChange={(e) => {
              reset();
              setUrl(e.target.value);
              setConsent(false);
            }}
          />
        </label>
        <label>
          Local server access token
          <input
            type="password"
            autoComplete="off"
            value={token}
            onChange={(e) => {
              reset();
              setToken(e.target.value);
            }}
          />
        </label>
        <label className="check">
          <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
          I trust this server and understand that it will receive audio for local processing.
        </label>
        <button className="primary" disabled={!consent} onClick={() => void browse('.', true)}>
          Choose model folder
        </button>
        {folder && (
          <div className="model-folders">
            <p>
              <strong>Server models / {folder.path === '.' ? '' : folder.path}</strong>
            </p>
            {folder.parent !== null && (
              <button className="text-button" onClick={() => void browse(folder.parent!)}>
                ↑ Parent folder
              </button>
            )}
            {folder.folders.map((child) => (
              <button
                className="folder-row"
                key={child.path}
                onClick={() => void browse(child.path)}
              >
                <span aria-hidden="true">▱</span> {child.name} <span aria-hidden="true">→</span>
              </button>
            ))}
            <p>
              {folder.compatibleFiles
                ? 'Encoder, decoder, joiner and tokens found. Loading will validate the model.'
                : 'Open a folder containing streaming sherpa-onnx encoder, decoder, joiner and tokens.txt files.'}
            </p>
            <button
              className="primary"
              disabled={!folder.compatibleFiles || !consent}
              onClick={() => void load()}
            >
              Load this model locally
            </button>
            <small>
              Parakeet Unified streaming is the verified model target. Offline/TDT exports, GGUF and
              arbitrary Hugging Face folders are not supported. Only load models you trust.
            </small>
          </div>
        )}
      </fieldset>
      {busy && (
        <p role="status">
          Preparing your server… Loading and warming up the model can take up to two minutes. Wait
          until connected before pressing BOOT.
        </p>
      )}
      {disabled && <p>Disconnect Glyph before changing models.</p>}
      <p role="alert">{error}</p>
    </section>
  );
}
