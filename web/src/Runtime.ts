import { Session } from './core/Session';
import type {
  Connection,
  Conversation,
  SessionState,
  Summary,
  SpeechRecognizer,
} from './core/types';
import { HistoryStore } from './data/HistoryStore';
import { WebSocketReceiver } from './network/WebSocketReceiver';
import { CloudRecognizer, validateCloud } from './speech/CloudRecognizer';
import type { CloudConfig } from './speech/CloudRecognizer';
import { LocalRecognizer } from './speech/LocalRecognizer';
import type { LocalConfig } from './speech/LocalRecognizer';

const idle: SessionState = {
  phase: 'idle',
  conversation: null,
  bytes: 0,
  packets: 0,
  sampleRate: 0,
  message: 'Connect your Glyph to begin.',
};
interface Snapshot {
  session: SessionState;
  connection: Connection;
  configured: boolean;
  speechMode: 'local' | 'cloud' | null;
  modelName: string;
  serverBridge: boolean;
  loading: boolean;
  conversations: Conversation[];
  summaries: Summary[];
  notice: string;
  storageError: string;
  stopping: boolean;
}
/** Activity-independent owner; browser foreground-only policy is explicit in App. */
export class Runtime {
  private state: Snapshot = {
    session: idle,
    connection: 'disconnected',
    configured: false,
    speechMode: null,
    modelName: '',
    serverBridge: false,
    loading: true,
    conversations: [],
    summaries: [],
    notice: '',
    storageError: '',
    stopping: false,
  };
  private listeners = new Set<() => void>();
  private history = new HistoryStore();
  private session?: Session;
  private receiver: WebSocketReceiver;
  private lastCheckpoint = '';
  private stopTimeout?: ReturnType<typeof setTimeout>;
  private refreshGeneration = 0;
  private destroyed = false;
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private update(value: Partial<Snapshot>) {
    if (this.destroyed) return;
    this.state = { ...this.state, ...value };
    for (const listener of this.listeners) listener();
  }
  constructor() {
    this.receiver = new WebSocketReceiver(
      (event) => this.session?.accept(event),
      (connection) => this.update({ connection }),
      (message) => {
        this.session?.connectionLost(message);
        this.update({ notice: message });
      },
    );
  }
  async init() {
    try {
      await this.history.recover();
      await this.refreshHistory();
    } catch {
      this.storageFailure();
    }
    this.update({ loading: false });
  }
  private storageFailure() {
    this.update({
      storageError:
        'History could not be saved or loaded. Copy visible words before closing; check browser storage.',
    });
  }
  private async refreshHistory() {
    const token = ++this.refreshGeneration;
    const [conversations, summaries] = await Promise.all([
      this.history.list<Conversation>('conversations'),
      this.history.list<Summary>('summaries'),
    ]);
    if (token === this.refreshGeneration) this.update({ conversations, summaries });
  }
  configure(config: CloudConfig) {
    this.configureRecognizer(
      new CloudRecognizer(validateCloud(config)),
      'cloud',
      config.model,
      'Cloud speech enabled for this tab. Audio will go to your chosen endpoint; provider charges may apply.',
    );
    this.receiver.setProxy(undefined);
    this.update({ serverBridge: false });
  }
  configureLocal(config: LocalConfig) {
    this.configureRecognizer(
      new LocalRecognizer(config),
      'local',
      config.name,
      'Local model ready on your self-hosted server. Audio is processed there; no cloud STT is used.',
    );
    this.receiver.setProxy(config.bridge ? { url: config.url, token: config.token } : undefined);
    this.update({ serverBridge: config.bridge });
  }
  private configureRecognizer(
    recognizer: SpeechRecognizer,
    speechMode: 'local' | 'cloud',
    modelName: string,
    notice: string,
  ) {
    if (this.state.connection !== 'disconnected' || this.session?.busy)
      throw new Error('Disconnect before changing speech settings.');
    // Key is owned only by this recognizer. Never stored in localStorage, IndexedDB, or the service worker.
    this.session = new Session(
      recognizer,
      (value) => {
        this.update({ session: value });
        if (value.phase !== 'receiving') {
          clearTimeout(this.stopTimeout);
          this.update({ stopping: false });
        }
        const row = value.conversation;
        if (!row?.text) return;
        const checkpoint = `${row.id}:${row.status}:${row.text}`;
        if (checkpoint === this.lastCheckpoint) return;
        this.lastCheckpoint = checkpoint;
        void this.history
          .save(row)
          .then(() => this.refreshHistory())
          .catch(() => {
            this.lastCheckpoint = '';
            this.storageFailure();
          });
      },
      () =>
        this.update({
          notice:
            'Transcript saved. AI summaries are the next migration step; no summary request was sent.',
        }),
      () => {
        this.receiver.requestStop();
        this.receiver.disconnect();
      },
    );
    this.update({
      configured: true,
      notice,
      speechMode,
      modelName,
    });
  }
  connect(address: string) {
    if (this.state.loading) throw new Error('Please wait for history to open.');
    if (!this.state.configured) throw new Error('Configure speech recognition first.');
    if (this.session?.busy) throw new Error('Wait for final words before reconnecting.');
    this.update({ notice: '' });
    this.receiver.connect(address);
  }
  stop() {
    if (this.state.session.phase !== 'receiving' || this.state.stopping) return;
    if (!this.receiver.requestStop()) {
      this.update({
        notice: 'Phone stop unavailable. Click BOOT to stop or update to transport-r5/r6 firmware.',
      });
      return;
    }
    this.update({ stopping: true, notice: 'Stopping Glyph… Waiting for its end acknowledgment.' });
    this.stopTimeout = setTimeout(
      () =>
        this.update({
          stopping: false,
          notice: 'Glyph has not confirmed stop. Click BOOT or tap Stop again.',
        }),
      5000,
    );
  }
  disconnect(message = 'Disconnected. Partial words kept.') {
    clearTimeout(this.stopTimeout);
    this.receiver.requestStop();
    this.session?.interrupt(message);
    this.receiver.disconnect();
    this.update({ notice: message, stopping: false });
  }
  async delete(id: string, summary: boolean) {
    if (this.session?.busy && id === this.state.session.conversation?.id && !summary)
      throw new Error('Stop this recording before deleting it.');
    await this.history.delete(id, summary);
    await this.refreshHistory();
  }
  forgetKey() {
    this.disconnect('Speech credentials removed from this tab.');
    this.session = undefined;
    this.receiver.setProxy(undefined);
    this.update({ configured: false, speechMode: null, modelName: '', serverBridge: false });
  }
  destroy() {
    this.disconnect();
    this.destroyed = true;
    this.listeners.clear();
  }
}
