import { useEffect, useRef, useState } from 'react';
import { BleProvisioner, bluetoothSupport } from '../network/BleProvisioner';
import type { ProvisionStatus } from '../network/BleProvisioner';
import { Modal } from './Modal';
import { Icon } from './Icon';

export function GlyphSetup({
  close,
  connected,
  manual,
}: {
  close: () => void;
  connected: (address: string) => void;
  manual: () => void;
}) {
  const [phase, setPhase] = useState<'idle' | 'choosing' | 'paired' | 'sending' | 'done'>('idle');
  const [message, setMessage] = useState(
    'Power on your Glyph. Tap Find nearby to choose it securely.',
  );
  const [device, setDevice] = useState<{ name: string; status: ProvisionStatus } | null>(null);
  const [ssid, setSsid] = useState(''),
    [password, setPassword] = useState('');
  const [fallback, setFallback] = useState(false);
  const client = useRef<BleProvisioner | null>(null);
  const alive = useRef(true);
  const unsupported = bluetoothSupport();
  useEffect(() => {
    alive.current = true;
    const hidden = () => {
      if (document.hidden) {
        client.current?.close();
        setPassword('');
        setPhase('idle');
        setMessage('Setup paused. Return here and select your Glyph again.');
      }
    };
    document.addEventListener('visibilitychange', hidden);
    return () => {
      alive.current = false;
      document.removeEventListener('visibilitychange', hidden);
      client.current?.close();
    };
  }, []);
  async function choose() {
    client.current?.close();
    const current = new BleProvisioner((message) => {
      if (alive.current) setMessage(message);
    });
    client.current = current;
    setPhase('choosing');
    setDevice(null);
    setPassword('');
    try {
      const selected = await current.choose();
      if (!alive.current || client.current !== current) {
        current.close();
        return;
      }
      setDevice(selected);
      if (selected.status.state === 'connected') {
        setPhase('done');
        setMessage('Glyph is already on Wi-Fi. Its address was discovered over Bluetooth.');
      } else {
        setPhase('paired');
        setMessage(
          'Selected Glyph. Verify the pairing PIN when prompted, then send Wi-Fi details.',
        );
      }
    } catch (error) {
      current.close();
      if (alive.current && client.current === current) {
        setPhase('idle');
        setMessage(
          error instanceof Error && error.name === 'NotFoundError'
            ? 'No device selected. Power-cycle your Glyph and try again.'
            : error instanceof Error
              ? error.message
              : 'Bluetooth connection failed.',
        );
      }
    }
  }
  async function send() {
    const current = client.current;
    if (!current) return;
    setPhase('sending');
    const key = password;
    setPassword('');
    try {
      const status = await current.provision(ssid, key);
      if (!alive.current || client.current !== current) return;
      setDevice((previous) => (previous ? { ...previous, status } : null));
      setPhase('done');
      setMessage('Wi-Fi connected. Your credentials are saved on the Glyph, not in this browser.');
    } catch (error) {
      if (alive.current && client.current === current) {
        setPhase('idle');
        setMessage(error instanceof Error ? error.message : 'Setup failed.');
      }
    }
  }
  return (
    <Modal title="Find your Glyph" close={close}>
      <p className="muted">A nearby connection. A simpler start.</p>
      <div
        className={`glyph-radar ${phase === 'choosing' || phase === 'sending' ? 'sweeping' : ''}`}
        aria-label={device ? `${device.name} selected` : 'No Glyph selected'}
        role="img"
      >
        <span className="radar-ring ring-one" />
        <span className="radar-ring ring-two" />
        <span className="radar-axis" />
        <span className="radar-center">
          <Icon name="wave" />
        </span>
        {device && (
          <span className="radar-device">
            <span />
            {device.name}
          </span>
        )}
      </div>
      <p className="radar-status" role="status">
        {unsupported ?? message}
      </p>
      {!unsupported && (
        <button
          className="primary"
          disabled={phase === 'choosing' || phase === 'sending'}
          onClick={() => void choose()}
        >
          {device ? 'Choose another Glyph' : 'Find nearby Glyph'}
        </button>
      )}
      <p className="muted">
        Your browser shows the nearby-device picker. This radar displays only the Glyph you select,
        not a silent scan or a physical location map.
      </p>
      {device && (
        <article className="selected-glyph">
          <Icon name="wave" />
          <span>
            <strong>{device.name}</strong>
            <small>
              {device.status.id} · {phase === 'done' ? 'Wi-Fi ready' : 'Bluetooth selected'}
            </small>
          </span>
        </article>
      )}
      {phase === 'paired' && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void send();
          }}
        >
          <p>
            Enable your phone hotspot now, or use shared 2.4 GHz Wi-Fi. Browsers cannot read saved
            Wi-Fi passwords.
          </p>
          <label>
            Wi-Fi / hotspot name
            <input
              autoComplete="off"
              value={ssid}
              onChange={(e) => setSsid(e.target.value)}
              required
            />
          </label>
          <label>
            Wi-Fi password
            <input
              type="password"
              autoComplete="off"
              spellCheck={false}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>
          <button className="primary" type="submit">
            Send settings & connect
          </button>
          <p className="muted">
            Use only your own Glyph and its private six-digit pairing PIN. Wi-Fi details are never
            stored by this page.
          </p>
        </form>
      )}
      {phase === 'done' && device && (
        <>
          <p>
            Glyph address: <strong>{device.status.address}</strong>
          </p>
          {location.protocol === 'https:' && (
            <p className="callout">
              Wi-Fi setup is complete. For HTTPS audio, configure your local model server with the
              --glyph-host bridge. Bluetooth alone does not bypass the board’s plain ws://
              restriction.
            </p>
          )}
          <button
            className="primary"
            onClick={() => {
              client.current?.close();
              connected(device.status.address);
            }}
          >
            Continue to voice
          </button>
        </>
      )}
      <div className="setup-alternatives">
        <button type="button" className="text-button" onClick={() => setFallback(!fallback)}>
          iPhone / Wi-Fi setup fallback
        </button>
        <button className="text-button" onClick={manual}>
          Enter an existing Glyph IP
        </button>
      </div>
      {fallback && (
        <div className="callout">
          <p>
            iPhone Safari does not support Web Bluetooth. For a new board, join{' '}
            <strong>GLYPH-Setup-xxxxxx</strong> (password <strong>glyphvoice</strong>), then open{' '}
            <a href="http://192.168.4.1" target="_blank" rel="noreferrer">
              the local setup page
            </a>
            .
          </p>
          <p>
            For a configured board, hold BOOT for five seconds while no audio app is connected. Save
            the Wi-Fi details, return to your usual network, and use the board’s IP address. The
            fallback uses a shared setup AP password—use it only in a trusted environment.
          </p>
        </div>
      )}
      <small>
        Requires transport-r7 firmware. BLE setup opens for five minutes after boot and closes when
        an audio client connects. PIN: USB startup output / private kit label.
      </small>
    </Modal>
  );
}
