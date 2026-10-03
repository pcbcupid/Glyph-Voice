import { Session, newId } from './core/Session';
import { SummaryClient } from './ai/SummaryClient';
import type { SummaryConfig } from './ai/SummaryClient';
import type { LocalConnection } from './speech/LocalRecognizer';
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
  summaryConfigured: boolean;
  summaryBusy: boolean;
  summaryMessage: string;
  summaryError: boolean;
  summaryNeedsSetup: boolean;
  summaryResult: Summary | null;
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
    summaryConfigured: false,
    summaryBusy: false,
    summaryMessage: '',
    summaryError: false,
    summaryNeedsSetup: false,
    summaryResult: null,
  };
  private listeners = new Set<() => void>();
  private history = new HistoryStore();
  private session?: Session;
  private receiver: WebSocketReceiver;
  private lastCheckpoint = '';
  private stopTimeout?: ReturnType<typeof setTimeout>;
  private refreshGeneration = 0;
  private destroyed = false;
  private localConnection?: LocalConnection;
  private summaryClient?: SummaryClient;
  private summaryController?: AbortController;
  private summarySource = '';
  private summaryGeneration = 0;
  private pendingSummary?: Conversation;
  private summarizeAfterStop = false;
  getSummaryConnection = () => this.localConnection;
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
      (message) => this.session?.waitingForAudio(message),
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
    this.localConnection = { url: config.url, token: config.token };
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
        if (
          value.phase === 'error' ||
          (value.phase === 'receiving' &&
            value.conversation?.id !== this.state.session.conversation?.id)
        )
          this.summarizeAfterStop = false;
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
      (row) => {
        const requested = this.summarizeAfterStop;
        this.summarizeAfterStop = false;
        if (requested || this.summaryClient?.config.automatic) this.requestSummary(row);
        else
          this.update({ notice: 'Transcript saved. Tap Summarize to create an English summary.' });
      },
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
    this.summarizeAfterStop = false;
    clearTimeout(this.stopTimeout);
    this.receiver.requestStop();
    this.session?.interrupt(message);
    this.receiver.disconnect();
    this.update({ notice: message, stopping: false });
  }
  async delete(id: string, summary: boolean) {
    if (this.session?.busy && id === this.state.session.conversation?.id && !summary)
      throw new Error('Stop this recording before deleting it.');
    if (!summary && this.summarySource === id) this.cancelSummary();
    if (this.pendingSummary?.id === id) this.pendingSummary = undefined;
    await this.history.delete(id, summary);
    if (summary && this.state.summaryResult?.id === id) this.update({ summaryResult: null });
    await this.refreshHistory();
  }
  configureSummary(config: SummaryConfig) {
    if (this.state.summaryBusy) throw new Error('Cancel or finish the current summary first.');
    this.summaryClient = new SummaryClient(config);
    this.update({
      summaryConfigured: true,
      summaryNeedsSetup: false,
      notice: 'Summary API configured for this tab.',
      summaryMessage: '',
      summaryError: false,
    });
    const pending = this.pendingSummary;
    this.pendingSummary = undefined;
    if (pending) this.requestSummary(pending);
  }
  dismissSummarySetup() {
    this.pendingSummary = undefined;
    this.update({ summaryNeedsSetup: false });
  }
  forgetSummary() {
    this.cancelSummary();
    this.summaryClient = undefined;
    this.pendingSummary = undefined;
    this.summarizeAfterStop = false;
    this.update({
      summaryConfigured: false,
      summaryNeedsSetup: false,
      notice: 'Summary API settings removed.',
      summaryMessage: '',
      summaryError: false,
    });
  }
  requestSummary(row?: Conversation) {
    if (!row && this.session?.busy) {
      this.summarizeAfterStop = true;
      if (this.state.session.phase === 'receiving') this.stop();
      return; // Only final text after hardware end + STT finish is summarized.
    }
    row ??= this.state.session.conversation ?? undefined;
    if (!row?.text.trim()) {
      this.update({ notice: 'No text to summarize.' });
      return;
    }
    if (this.state.summaryBusy) {
      this.update({
        notice: 'A summary is already running. This transcript is saved; summarize it afterward.',
      });
      return;
    }
    if (!this.summaryClient) {
      this.pendingSummary = { ...row };
      this.update({ summaryNeedsSetup: true });
      return;
    }
    void this.runSummary({ ...row }, this.summaryClient);
  }
  private async runSummary(row: Conversation, client: SummaryClient) {
    const generation = ++this.summaryGeneration;
    const controller = new AbortController();
    this.summaryController = controller;
    this.summarySource = row.id;
    this.update({
      summaryBusy: true,
      summaryError: false,
      summaryMessage: `Waiting for ${client.config.provider === 'deepseek' ? 'DeepSeek' : 'OpenAI'} through your local server…`,
      notice: 'Summarizing your thoughts…',
    });
    try {
      const text = await client.summarize(row.text, controller.signal);
      if (generation !== this.summaryGeneration || this.destroyed) return;
      this.update({ summaryMessage: 'AI response received. Saving summary in this browser…' });
      const summary: Summary = {
        id: newId(),
        sourceId: row.id,
        source: row.text,
        text,
        created: Date.now(),
      };
      let saved: boolean;
      try {
        saved = await this.history.saveSummary(summary);
      } catch {
        if (generation === this.summaryGeneration && !this.destroyed) {
          this.update({
            summaryResult: summary,
            summaryError: true,
            summaryMessage:
              'Summary received, but browser history could not save it. Copy the visible summary before closing this tab.',
            notice: 'Summary received, but could not be saved. Copy it before closing.',
          });
        }
        return;
      }
      if (generation !== this.summaryGeneration || this.destroyed) {
        if (saved) await this.history.delete(summary.id, true);
        return;
      }
      if (saved) {
        this.update({
          summaryResult: summary,
          notice: 'Summary saved. Your original transcript is kept.',
          summaryMessage: 'Summary saved. Your original transcript is kept.',
        });
        await this.refreshHistory();
      } else {
        this.update({
          summaryError: true,
          summaryMessage:
            'The source was deleted before this summary could be saved. No summary was restored.',
          notice: 'Summary discarded because its source was deleted.',
        });
      }
    } catch (e) {
      if (generation === this.summaryGeneration) {
        const message = e instanceof Error ? e.message : 'Summary failed. Original words kept.';
        this.update({
          notice: message,
          summaryMessage: message,
          summaryError: true,
        });
      }
    } finally {
      if (generation === this.summaryGeneration) {
        this.summaryController = undefined;
        this.summarySource = '';
        this.update({ summaryBusy: false });
      }
    }
  }
  cancelSummary() {
    ++this.summaryGeneration;
    this.summaryController?.abort();
    this.summaryController = undefined;
    this.summarySource = '';
    this.update({
      summaryBusy: false,
      notice: 'Summary cancelled. Provider may already have received/billed the text.',
      summaryMessage:
        'Summary cancelled. Your transcript is kept. Provider charges may still apply.',
      summaryError: false,
    });
  }
  forgetKey() {
    this.disconnect('Speech credentials removed from this tab.');
    this.session = undefined;
    this.localConnection = undefined;
    this.receiver.setProxy(undefined);
    this.update({ configured: false, speechMode: null, modelName: '', serverBridge: false });
  }
  destroy() {
    this.cancelSummary();
    this.disconnect();
    this.destroyed = true;
    this.listeners.clear();
  }
}
