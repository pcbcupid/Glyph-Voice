import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WebSocketReceiver } from '../src/network/WebSocketReceiver';

class Socket {
  static OPEN = 1;
  static instances: Socket[] = [];
  readyState = Socket.OPEN;
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((message: { data: string | ArrayBuffer }) => void) | null = null;
  send = vi.fn();
  close = vi.fn();
  constructor(readonly url: string) {
    Socket.instances.push(this);
  }
  receive(data: string | ArrayBuffer) {
    this.onmessage?.({ data });
  }
}
const start = JSON.stringify({
  type: 'start',
  version: 1,
  id: 'long',
  sampleRate: 16000,
  channels: 1,
  encoding: 'pcm_s16le',
  control: 'stop-v1',
});

describe('recording transport lifetime', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    Socket.instances = [];
    vi.stubGlobal('WebSocket', Socket);
    vi.stubGlobal('location', { protocol: 'http:' });
  });
  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  function setup() {
    const event = vi.fn(),
      connection = vi.fn(),
      error = vi.fn(),
      waiting = vi.fn();
    const receiver = new WebSocketReceiver(event, connection, error, waiting);
    receiver.connect('192.168.0.123:8080');
    const socket = Socket.instances[0];
    socket.onopen?.();
    socket.receive(start);
    return { receiver, socket, event, connection, error, waiting };
  }

  it('keeps ten minutes of continuous PCM open until an explicit matching end', () => {
    const { receiver, socket, event, error, waiting } = setup();
    // Virtual wall time catches total-session timers without a ten-minute unit test.
    for (let packet = 0; packet < 1200; packet++) {
      socket.receive(new ArrayBuffer(16000));
      vi.advanceTimersByTime(500);
    }
    expect(error).not.toHaveBeenCalled();
    expect(waiting).not.toHaveBeenCalled();
    expect(socket.close).not.toHaveBeenCalled();
    expect(receiver.requestStop()).toBe(true);
    expect(socket.send).toHaveBeenLastCalledWith('STOP long');
    expect(event.mock.lastCall?.[0].type).toBe('audio');
    socket.receive(new ArrayBuffer(640));
    socket.receive(JSON.stringify({ type: 'end', id: 'long', bytes: 19200640 }));
    expect(event.mock.lastCall?.[0]).toEqual({ type: 'end', id: 'long' });
    vi.advanceTimersByTime(60000);
    expect(waiting).not.toHaveBeenCalled();
    expect(receiver.requestStop()).toBe(false);
    receiver.disconnect();
  });

  it('warns during an audio pause without ending, reconnecting or losing the stop control', () => {
    const { receiver, socket, event, connection, error, waiting } = setup();
    socket.receive(new ArrayBuffer(3200));
    vi.advanceTimersByTime(60000);
    expect(waiting).toHaveBeenCalledTimes(1);
    expect(error).not.toHaveBeenCalled();
    expect(socket.close).not.toHaveBeenCalled();
    expect(connection.mock.lastCall?.[0]).toBe('connected');
    socket.receive(new ArrayBuffer(3200));
    expect(receiver.requestStop()).toBe(true);
    socket.receive(JSON.stringify({ type: 'end', id: 'long', bytes: 6400 }));
    expect(event.mock.lastCall?.[0].type).toBe('end');
    expect(Socket.instances).toHaveLength(1);
    receiver.disconnect();
  });

  it('still reconnects on a real socket loss and rejects corrupted packets', () => {
    const { receiver, socket, error } = setup();
    socket.onclose?.();
    expect(error).toHaveBeenCalledWith(expect.stringContaining('connection lost'));
    vi.advanceTimersByTime(1000);
    const next = Socket.instances[1];
    next.onopen?.();
    next.receive('{');
    expect(error).toHaveBeenLastCalledWith('Malformed control JSON.');
    expect(next.close).toHaveBeenCalled();
    receiver.disconnect();
  });
});
