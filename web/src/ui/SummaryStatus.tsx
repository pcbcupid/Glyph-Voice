import { useEffect, useState } from 'react';

export function SummaryStatus({
  busy,
  message,
  error,
  settings,
}: {
  busy: boolean;
  message: string;
  error: boolean;
  settings: () => void;
}) {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    if (!busy) return;
    const began = Date.now();
    setSeconds(0);
    const timer = setInterval(() => setSeconds(Math.floor((Date.now() - began) / 1000)), 1000);
    return () => clearInterval(timer);
  }, [busy]);
  if (!message) return null;
  return (
    <aside className="summary-status" aria-label="Summary status">
      <p role={error ? 'alert' : undefined}>{message}</p>
      {busy && (
        <small>
          {seconds}s elapsed · You can cancel below. Slow provider requests time out; they are not
          retried automatically.
        </small>
      )}
      {error && (
        <>
          <small>Your original transcript is unchanged.</small>
          <button className="text-button" onClick={settings}>
            Check summary settings
          </button>
        </>
      )}
    </aside>
  );
}
