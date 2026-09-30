export interface AudioFormat {
  sampleRate: number;
  channels: 1;
  encoding: 'pcm_s16le';
}
export type AudioEvent =
  | { type: 'start'; id: string; format: AudioFormat; remoteStop: boolean }
  | { type: 'audio'; pcm: Uint8Array }
  | { type: 'end'; id: string };
export type Connection = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';
export interface AudioReceiver {
  connect(address: string): void;
  disconnect(): void;
  requestStop(): boolean;
}
export interface SpeechStream {
  accept(pcm: Uint8Array): Promise<string>;
  finish(): Promise<string>;
  cancel(): void;
}
export interface SpeechRecognizer {
  open(format: AudioFormat): SpeechStream;
}
export interface Conversation {
  id: string;
  text: string;
  created: number;
  status: 'recording' | 'complete' | 'interrupted';
}
export interface Summary {
  id: string;
  sourceId: string;
  source: string;
  text: string;
  created: number;
}
export interface SessionState {
  phase: 'idle' | 'receiving' | 'processing' | 'result' | 'error';
  conversation: Conversation | null;
  bytes: number;
  packets: number;
  sampleRate: number;
  message: string;
}
