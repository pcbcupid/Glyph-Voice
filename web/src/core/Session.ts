import type {
  AudioEvent,
  Conversation,
  SessionState,
  SpeechRecognizer,
  SpeechStream,
} from './types';

type Job = {
  stream: SpeechStream;
  id: string;
  queue: Uint8Array[];
  queuedBytes: number;
  ended: boolean;
  cancelled: boolean;
  running: boolean;
};
/** Browser-independent session owner. Receive never waits for recognition/network inference. */
export class Session {
  state: SessionState = {
    phase: 'idle',
    conversation: null,
    bytes: 0,
    packets: 0,
    sampleRate: 0,
    message: 'Connect your Glyph to begin.',
  };
  private job: Job | null = null;
  constructor(
    private recognizer: SpeechRecognizer,
    private publish: (state: SessionState) => void,
    private completed: (value: Conversation) => void = () => {},
    private stopOnFailure: () => void = () => {},
  ) {}
  get busy() {
    return this.job !== null;
  }
  private emit() {
    this.publish({ ...this.state });
  }
  accept(event: AudioEvent) {
    if (event.type === 'start') {
      if (this.job) throw new Error('Still finalizing. Stop the new recording and wait.');
      const stream = this.recognizer.open(event.format);
      this.job = {
        stream,
        id: event.id,
        queue: [],
        queuedBytes: 0,
        ended: false,
        cancelled: false,
        running: false,
      };
      this.state = {
        phase: 'receiving',
        conversation: { id: newId(), created: Date.now(), text: '', status: 'recording' },
        bytes: 0,
        packets: 0,
        sampleRate: event.format.sampleRate,
        message: 'Listening · click BOOT again to stop',
      };
      this.emit();
      return;
    }
    const job = this.job;
    if (!job) return;
    if (event.type === 'audio') {
      if (job.ended) throw new Error('Audio after end.');
      if (event.pcm.length < 2 || event.pcm.length > 16384 || event.pcm.length % 2)
        throw new Error('Invalid PCM packet.');
      if (
        job.queuedBytes + event.pcm.length > this.state.sampleRate * 2 * 30 ||
        job.queue.length >= 4096
      ) {
        this.interrupt('Speech processing fell behind. Partial words kept; reconnect to retry.');
        this.stopOnFailure();
        return;
      }
      job.queue.push(event.pcm.slice());
      job.queuedBytes += event.pcm.length;
      this.state = {
        ...this.state,
        bytes: this.state.bytes + event.pcm.length,
        packets: this.state.packets + 1,
      };
      if (this.state.packets === 1 || this.state.packets % 25 === 0) this.emit();
    } else {
      if (job.id !== event.id) throw new Error('Recording ID mismatch.');
      if (this.state.bytes < this.state.sampleRate / 5) {
        this.interrupt(
          this.state.bytes ? 'Recording too short. Please try again.' : 'No audio received.',
        );
        return;
      }
      job.ended = true;
      this.state = { ...this.state, phase: 'processing', message: 'Finalizing the last words…' };
      this.emit();
    }
    void this.drain(job);
  }
  private async drain(job: Job) {
    if (job.running || job.cancelled) return;
    job.running = true;
    try {
      while (job.queue.length && !job.cancelled) {
        const pcm = job.queue.shift()!;
        job.queuedBytes -= pcm.length;
        try {
          const text = await job.stream.accept(pcm);
          if (!job.cancelled && text && this.state.conversation?.text !== text)
            this.words(text, 'recording');
        } finally {
          pcm.fill(0);
        }
      }
      if (job.ended && !job.cancelled) {
        const text = await job.stream.finish();
        if (job.cancelled) return;
        const partial = this.state.conversation?.text ?? '';
        const interrupted = !text && !!partial;
        this.state = {
          ...this.state,
          phase: 'result',
          message: interrupted
            ? 'Final response was empty. Partial words kept.'
            : text
              ? 'Saved in this browser. Click BOOT for a new recording.'
              : 'No speech detected.',
        };
        this.job = null;
        this.words(text || partial, interrupted ? 'interrupted' : 'complete');
        if (text && !interrupted) this.completed(this.state.conversation!);
      }
    } catch (e) {
      if (!job.cancelled) {
        this.interrupt(
          e instanceof Error ? e.message : 'Transcription failed. Partial words kept.',
        );
        this.stopOnFailure();
      }
    } finally {
      job.running = false;
    }
  }
  private words(text: string, status: Conversation['status']) {
    if (this.state.conversation)
      this.state = { ...this.state, conversation: { ...this.state.conversation, text, status } };
    this.emit();
  }
  /** A valid end may still finalize after a socket disconnect. Explicit leave/cancel always interrupts. */
  connectionLost(message: string) {
    if (!this.job?.ended) this.interrupt(message);
  }
  interrupt(message: string) {
    const job = this.job;
    if (job) {
      job.cancelled = true;
      job.stream.cancel();
      for (const pcm of job.queue) pcm.fill(0);
      job.queue = [];
      job.queuedBytes = 0;
      this.job = null;
    }
    this.state = {
      ...this.state,
      phase: 'error',
      message,
      conversation: this.state.conversation
        ? {
            ...this.state.conversation,
            status: job ? 'interrupted' : this.state.conversation.status,
          }
        : null,
    };
    this.emit();
  }
}

export function newId() {
  // randomUUID is secure-context-only; getRandomValues also works for LAN HTTP development.
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}
