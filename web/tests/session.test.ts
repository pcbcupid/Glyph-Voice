import { describe, expect, it, vi } from 'vitest';
import { Session } from '../src/core/Session';
import type { SpeechStream } from '../src/core/types';

const start = {
  type: 'start' as const,
  id: 'r1',
  remoteStop: true,
  format: { sampleRate: 16000, channels: 1 as const, encoding: 'pcm_s16le' as const },
};
const end = { type: 'end' as const, id: 'r1' };
function setup(stream?: Partial<SpeechStream>) {
  const fake = {
    accept: vi.fn(async () => 'Partial words'),
    finish: vi.fn(async () => 'Final words.'),
    cancel: vi.fn(),
    ...stream,
  };
  const complete = vi.fn(),
    stop = vi.fn(),
    publish = vi.fn();
  const session = new Session({ open: () => fake }, publish, complete, stop);
  return { session, complete, stop, fake };
}
function audio(session: Session) {
  session.accept({ type: 'audio', pcm: new Uint8Array(3200) });
}
describe('stream lifecycle', () => {
  it('finishes after accepted audio, not at the stop request, and emits completion once', async () => {
    const { session, complete } = setup();
    session.accept(start);
    audio(session);
    session.accept(end);
    await vi.waitFor(() => expect(session.state.phase).toBe('result'));
    expect(session.state.conversation).toMatchObject({ text: 'Final words.', status: 'complete' });
    expect(complete).toHaveBeenCalledTimes(1);
    session.accept(end);
    expect(complete).toHaveBeenCalledTimes(1);
  });
  it('preserves partial words on disconnect and clears only at the next start', async () => {
    const { session } = setup();
    session.accept(start);
    audio(session);
    await vi.waitFor(() => expect(session.state.conversation?.text).toBe('Partial words'));
    session.connectionLost('Disconnected');
    expect(session.state.conversation?.status).toBe('interrupted');
    expect(session.state.conversation?.text).toBe('Partial words');
    session.accept({ ...start, id: 'r2' });
    expect(session.state.conversation?.text).toBe('');
  });
  it('allows a clean end to finalize after transport disconnect', async () => {
    const { session, complete } = setup();
    session.accept(start);
    audio(session);
    session.accept(end);
    session.connectionLost('Disconnected');
    await vi.waitFor(() => expect(complete).toHaveBeenCalledTimes(1));
  });
  it('preserves an audio pause as live and resumes on the very next packet', async () => {
    const { session, fake, complete } = setup();
    session.accept(start);
    audio(session);
    await vi.waitFor(() => expect(session.state.conversation?.text).toBe('Partial words'));
    session.waitingForAudio('Waiting for Glyph audio');
    expect(session.state.phase).toBe('receiving');
    expect(session.busy).toBe(true);
    expect(session.state.conversation?.text).toBe('Partial words');
    expect(fake.cancel).not.toHaveBeenCalled();
    expect(fake.finish).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
    audio(session);
    expect(session.state.message).toContain('Listening');
    session.accept(end);
    await vi.waitFor(() => expect(complete).toHaveBeenCalledTimes(1));
    session.waitingForAudio('Stale warning');
    expect(session.state.message).not.toBe('Stale warning');
  });
  it('does not publish late inference after explicit cancellation', async () => {
    let resolve!: (text: string) => void;
    const { session, complete } = setup({
      accept: () =>
        new Promise((r) => {
          resolve = r;
        }),
    });
    session.accept(start);
    audio(session);
    session.interrupt('Hidden');
    resolve('Late words');
    await Promise.resolve();
    await Promise.resolve();
    expect(session.state.conversation?.text).toBe('');
    expect(complete).not.toHaveBeenCalled();
  });
  it('rejects a new recording during finalization', () => {
    const { session } = setup();
    session.accept(start);
    audio(session);
    session.accept(end);
    expect(() => session.accept({ ...start, id: 'r2' })).toThrow('Still finalizing');
  });
  it.each([0, 100])('does not transcribe an empty or short recording (%i bytes)', (bytes) => {
    const { session, complete } = setup();
    session.accept(start);
    if (bytes) session.accept({ type: 'audio', pcm: new Uint8Array(bytes) });
    session.accept(end);
    expect(session.state.phase).toBe('error');
    expect(complete).not.toHaveBeenCalled();
  });
  it('caps backlog and requests hardware stop without discarding displayed text', () => {
    const { session, stop, fake } = setup({ accept: () => new Promise(() => {}) });
    session.accept(start);
    for (let i = 0; i < 100; i++) session.accept({ type: 'audio', pcm: new Uint8Array(16384) });
    expect(session.state.phase).toBe('error');
    expect(stop).toHaveBeenCalledTimes(1);
    expect(fake.cancel).toHaveBeenCalled();
  });
  it('keeps a nonempty partial if finalization returns empty; never marks it complete', async () => {
    const { session, complete } = setup({ finish: async () => '' });
    session.accept(start);
    audio(session);
    session.accept(end);
    await vi.waitFor(() => expect(session.state.phase).toBe('result'));
    expect(session.state.conversation).toMatchObject({
      text: 'Partial words',
      status: 'interrupted',
    });
    expect(complete).not.toHaveBeenCalled();
  });
  it('fails closed when the speech provider fails', async () => {
    const { session, stop } = setup({
      accept: async () => {
        throw new Error('Provider failure');
      },
    });
    session.accept(start);
    audio(session);
    await vi.waitFor(() => expect(stop).toHaveBeenCalledTimes(1));
    expect(session.state.message).toBe('Provider failure');
  });
});
