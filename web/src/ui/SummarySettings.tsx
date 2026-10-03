import { useState } from 'react';
import { SUMMARY_PROVIDERS } from '../ai/SummaryClient';
import type { SummaryConfig } from '../ai/SummaryClient';
import type { LocalConnection } from '../speech/LocalRecognizer';

export function SummarySettings({
  connection,
  configured,
  save,
  forget,
}: {
  connection?: LocalConnection;
  configured: boolean;
  save: (config: SummaryConfig) => void;
  forget: () => void;
}) {
  const [provider, setProvider] = useState<SummaryConfig['provider']>('deepseek');
  const [model, setModel] = useState(SUMMARY_PROVIDERS.deepseek.model);
  const [key, setKey] = useState('');
  const [url, setUrl] = useState(
    connection?.url ?? (location.port === '5173' ? 'http://localhost:8765' : location.origin),
  );
  const [token, setToken] = useState(connection?.token ?? '');
  const [automatic, setAutomatic] = useState(true);
  const [consent, setConsent] = useState(false);
  const [error, setError] = useState('');
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        try {
          save({ provider, model, key, server: { url, token }, automatic, consent });
          setKey('');
        } catch (e) {
          setError(e instanceof Error ? e.message : 'Check summary settings.');
        }
      }}
    >
      <p>
        Only transcript text is sent to your chosen AI provider through your local server. Local
        Parakeet audio stays local. Internet and provider charges apply.
      </p>
      <label>
        AI provider
        <select
          value={provider}
          onChange={(e) => {
            const value = e.target.value as SummaryConfig['provider'];
            setProvider(value);
            setModel(SUMMARY_PROVIDERS[value].model);
            setKey('');
            setConsent(false);
          }}
        >
          {Object.entries(SUMMARY_PROVIDERS).map(([id, p]) => (
            <option value={id} key={id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Summary model
        <input value={model} onChange={(e) => setModel(e.target.value)} required />
      </label>
      <label>
        Summary API key
        <input
          type="password"
          autoComplete="off"
          value={key}
          onChange={(e) => setKey(e.target.value)}
          required
        />
      </label>
      <details open={!connection}>
        <summary>Local server connection</summary>
        <label>
          Summary server URL
          <input
            type="url"
            value={url}
            onChange={(e) => {
              setUrl(e.target.value);
              setToken('');
              setConsent(false);
            }}
            required
          />
        </label>
        <label>
          Summary server access token
          <input
            type="password"
            autoComplete="off"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            required
          />
        </label>
      </details>
      <label className="check">
        <input
          type="checkbox"
          checked={automatic}
          onChange={(e) => setAutomatic(e.target.checked)}
        />
        Automatically summarize when recording finishes
      </label>
      <label className="check">
        <input
          type="checkbox"
          checked={consent}
          onChange={(e) => setConsent(e.target.checked)}
          required
        />
        I allow transcript text and my provider key to pass through this trusted server to the
        selected provider, including automatic summaries if enabled.
      </label>
      <button className="primary" type="submit" disabled={!consent}>
        Save summary settings
      </button>
      {configured && (
        <button className="text-button" type="button" onClick={forget}>
          Remove summary API settings
        </button>
      )}
      <p role="alert">{error}</p>
      <small>
        Keys stay in this tab’s memory, not history or server storage. Use personal limited keys.
        Refreshing clears settings. Saving alone makes no provider request, unless you have a
        pending Summarize action.
      </small>
    </form>
  );
}
