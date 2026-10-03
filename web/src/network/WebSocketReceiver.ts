import { GlyphProtocol, localEndpoint } from '../core/protocol';
import type { AudioEvent, AudioReceiver, Connection } from '../core/types';
import { validateLocal } from '../speech/LocalRecognizer';
import type { LocalConnection } from '../speech/LocalRecognizer';

export class WebSocketReceiver implements AudioReceiver {
  private protocol = new GlyphProtocol();
  private socket: WebSocket | null = null;
  private retry?: ReturnType<typeof setTimeout>;
  private deadline?: ReturnType<typeof setTimeout>;
  private wanted = false;
  private generation = 0;
  private attempt = 0;
  private url = '';
  private address = '';
  private proxy?: LocalConnection;
  setProxy(config?: LocalConnection) {
    this.proxy = config ? validateLocal(config) : undefined;
  }
  constructor(
    private event: (event: AudioEvent) => void,
    private connection: (value: Connection) => void,
    private error: (message: string) => void,
    private waiting: (message: string) => void = () => {},
  ) {}
  connect(address: string) {
    const direct = localEndpoint(address, this.proxy ? 'http:' : location.protocol);
    const url = this.proxy ? this.proxy.url.replace(/^http/, 'ws') + '/api/glyph' : direct;
    this.disconnect();
    this.url = url;
    this.address = address.trim();
    this.wanted = true;
    this.attempt = 0;
    this.open();
  }
  private release() {
    ++this.generation;
    clearTimeout(this.deadline);
    this.protocol.reset();
    if (this.socket) {
      this.socket.onopen = this.socket.onclose = this.socket.onerror = this.socket.onmessage = null;
      this.socket.close();
      this.socket = null;
    }
  }
  private open() {
    if (!this.wanted) return;
    const token = ++this.generation;
    this.connection(this.attempt ? 'reconnecting' : 'connecting');
    try {
      const socket = new WebSocket(this.url);
      this.socket = socket;
      let ready = !this.proxy;
      socket.binaryType = 'arraybuffer';
      this.deadline = setTimeout(
        () => this.fail('Glyph did not answer. Check power and Wi-Fi.'),
        5000,
      );
      socket.onopen = () => {
        if (token !== this.generation) return;
        if (this.proxy) {
          socket.send(JSON.stringify({ token: this.proxy.token, address: this.address }));
          return;
        }
        clearTimeout(this.deadline);
        this.attempt = 0;
        this.connection('connected');
      };
      socket.onmessage = (message) => {
        if (token !== this.generation) return;
        try {
          if (!ready) {
            const reply = typeof message.data === 'string' ? JSON.parse(message.data) : null;
            if (reply?.proxy !== 'ready')
              throw new Error(
                'Glyph bridge could not connect. Check the server token, --glyph-host and board network.',
              );
            ready = true;
            clearTimeout(this.deadline);
            this.attempt = 0;
            this.connection('connected');
            return;
          }
          if (typeof message.data !== 'string' && !(message.data instanceof ArrayBuffer))
            throw new Error('Unexpected packet type.');
          const value = this.protocol.accept(message.data);
          clearTimeout(this.deadline);
          if (this.protocol.recording)
            this.deadline = setTimeout(
              () =>
                this.waiting(
                  'Waiting for Glyph audio… The connection is still open; recording has not been stopped.',
                ),
              5000,
            );
          this.event(value);
        } catch (e) {
          this.fail(e instanceof Error ? e.message : 'Audio protocol error.');
        }
      };
      socket.onclose = socket.onerror = () => {
        if (token === this.generation)
          this.fail('Glyph connection lost. Words kept; reconnecting…');
      };
    } catch {
      this.fail(
        'Browser could not connect. Check the address, network permission and page security.',
      );
    }
  }
  private fail(message: string) {
    this.release();
    if (!this.wanted) return;
    this.error(message);
    this.connection('reconnecting');
    this.retry = setTimeout(
      () => this.open(),
      Math.min(15, 2 ** Math.min(this.attempt++, 4)) * 1000,
    );
  }
  requestStop() {
    const command = this.protocol.stopCommand();
    if (!command || this.socket?.readyState !== WebSocket.OPEN) return false;
    try {
      this.socket.send(command);
      return true;
    } catch {
      return false;
    }
  }
  disconnect() {
    this.wanted = false;
    clearTimeout(this.retry);
    this.release();
    this.connection('disconnected');
  }
}
