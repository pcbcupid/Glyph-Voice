package com.pcbcupid.voice.core;

import com.pcbcupid.voice.audio.*;
import com.pcbcupid.voice.speech.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.pcbcupid.voice.core.VoiceState.Phase;

/** Bounded live audio handoff; socket callbacks never execute model inference. */
public final class StreamingTranscriptionSession implements AudioReceiver.Listener, AutoCloseable {
    // Headroom for temporary OS scheduling/thermal slowdowns, not recording length.
    public static final int MAX_BACKLOG_SECONDS = 30;
    private final StreamingSpeechRecognizer recognizer;
    private final ExecutorService worker;
    private Consumer<VoiceState> observer = state -> {};
    private AudioReceiver.Connection connection = AudioReceiver.Connection.DISCONNECTED;
    private Phase phase = Phase.IDLE;
    private String text = "", message = "Connect your Glyph to begin.";
    private long recordingSequence;
    private boolean complete, interrupted;
    private boolean ready, closed;
    private Job active;
    private int sampleRate;
    private long packets, bytes;
    private long processingMillis;
    private long recognizedBytes, recognitionWorkNanos;

    private static final class Job {
        final String id;
        final AudioFormat format;
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4096);
        volatile boolean cancelled, finished, overloaded;
        Thread thread;
        StreamingSpeechRecognizer.Stream stream;
        int queuedBytes;
        Job(String id, AudioFormat format) { this.id = id; this.format = format; }
    }
    public StreamingTranscriptionSession(StreamingSpeechRecognizer recognizer, ExecutorService worker) {
        this.recognizer = recognizer;
        this.worker = worker;
    }
    public synchronized void observe(Consumer<VoiceState> observer) { this.observer = observer; emit(); }
    public synchronized void setReady(boolean ready) { this.ready = ready; emit(); }
    public synchronized boolean isBusy() { return active != null; }
    public synchronized double bufferedSeconds() {
        return active == null ? 0 : active.queuedBytes / (active.format.sampleRate * 2.0);
    }
    /** Wall time spent feeding/decoding divided by consumed audio; >1 cannot sustain live input. */
    public synchronized double recognitionWorkRatio() {
        return recognizedBytes == 0 ? 0 : recognitionWorkNanos / 1_000_000_000.0
                / (recognizedBytes / (sampleRate * 2.0));
    }
    public synchronized VoiceState snapshot() {
        return new VoiceState(connection, phase, text, message, packets, bytes, sampleRate,
                sampleRate == 0 ? 0 : bytes / (2.0 * sampleRate), processingMillis,
                recordingSequence, complete, interrupted);
    }
    private void emit() { if (!closed) observer.accept(snapshot()); }

    @Override public synchronized void onConnection(AudioReceiver.Connection value) {
        if (closed) return;
        connection = value;
        if (value != AudioReceiver.Connection.CONNECTED && active != null && !active.finished) {
            cancelActive();
            phase = Phase.ERROR;
            message = "Connection lost. Words kept; recording interrupted. Reconnect and click BOOT to start again.";
        } else if (active == null) {
            phase = interrupted ? Phase.ERROR : text.isEmpty() ? Phase.IDLE : Phase.RESULT;
            message = value == AudioReceiver.Connection.CONNECTED
                    ? (interrupted ? "Interrupted words kept. Click BOOT for a new recording."
                    : "Click BOOT to start; click again to stop.")
                    : (text.isEmpty() ? "Connect your Glyph to begin." : "Words kept. Reconnect when ready.");
        }
        emit();
    }
    @Override public synchronized void onStart(String id, AudioFormat format) {
        if (closed) return;
        if (active != null) {
            message = "Still finishing. Click BOOT to stop the new stream, then wait before starting again.";
            emit();
            return;
        }
        if (!ready) { onError("Streaming speech model is not ready. Please wait."); return; }
        Job job = new Job(id, format);
        active = job;
        packets = bytes = 0;
        processingMillis = 0;
        recognizedBytes = recognitionWorkNanos = 0;
        sampleRate = format.sampleRate;
        recordingSequence++;
        complete = interrupted = false;
        text = "";
        phase = Phase.RECEIVING;
        message = "Listening live… Click BOOT again to stop.";
        emit();
        worker.execute(() -> recognize(job));
    }
    @Override public synchronized void onAudio(byte[] pcm) {
        Job job = active;
        if (closed || job == null || job.cancelled || job.finished) return;
        if (pcm.length < 2 || pcm.length > AudioBuffer.MAX_PACKET_BYTES || pcm.length % 2 != 0) {
            onError("Invalid PCM packet: expected aligned mono PCM16."); return;
        }
        if ((long) job.queuedBytes + pcm.length > (long) sampleRate * 2 * MAX_BACKLOG_SECONDS) {
            finishOverloaded(job); return;
        }
        byte[] copy = pcm.clone();
        if (!job.queue.offer(copy)) {
            Arrays.fill(copy, (byte) 0);
            finishOverloaded(job); return;
        }
        job.queuedBytes += copy.length;
        bytes += copy.length;
        packets++;
        if (packets == 1 || packets % 25 == 0) {
            message = bufferedSeconds() >= 4
                    ? "Catching up · " + (int) bufferedSeconds() + " seconds buffered. You can click BOOT to stop and finish."
                    : "Listening live… Click BOOT again to stop.";
            emit();
        }
    }
    private void finishOverloaded(Job job) {
        // Stop accepting new samples, but drain every sample already accepted.
        // An overload must never discard the entire pending speech buffer.
        job.overloaded = true;
        job.finished = true;
        interrupted = true;
        phase = Phase.PROCESSING;
        message = "Processing fell over 30 seconds behind. Click BOOT to stop. Finishing buffered speech; recording is incomplete.";
        emit();
    }
    @Override public synchronized void onEnd(String id) {
        if (closed || active == null || active.cancelled || active.finished) return;
        if (!active.id.equals(id)) { onError("Recording id mismatch."); return; }
        if (bytes == 0) { onError("No audio received."); return; }
        if (bytes < sampleRate / 5) { onError("Recording too short. Speak before clicking BOOT to stop."); return; }
        active.finished = true;
        phase = Phase.PROCESSING;
        message = "Finalizing the last words…";
        emit();
    }
    private void recognize(Job job) {
        try {
            synchronized (this) {
                if (closed || job.cancelled) return;
                job.thread = Thread.currentThread();
            }
            try (StreamingSpeechRecognizer.Stream stream = recognizer.openStream(job.format.sampleRate)) {
                synchronized (this) { job.stream = stream; if (job.cancelled) stream.cancel(); }
                while (!job.cancelled) {
                    byte[] chunk = job.queue.poll(50, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        synchronized (this) { job.queuedBytes = Math.max(0, job.queuedBytes - chunk.length); }
                        try {
                            if (job.cancelled) break;
                            long workStarted = System.nanoTime();
                            String partial = stream.accept(chunk);
                            synchronized (this) {
                                recognitionWorkNanos += System.nanoTime() - workStarted;
                                recognizedBytes += chunk.length;
                                if (!closed && !job.cancelled && !partial.isEmpty() && !partial.equals(text)) {
                                    text = partial;
                                    emit();
                                }
                            }
                        } finally { Arrays.fill(chunk, (byte) 0); }
                    }
                    if (job.finished && job.queue.isEmpty() && !job.cancelled) {
                        TranscriptionResult result = stream.finish();
                        synchronized (this) {
                            if (!closed && !job.cancelled) {
                                // An empty final result must not erase words already shown.
                                interrupted = job.overloaded || (result.text.isEmpty() && !text.isEmpty());
                                if (!result.text.isEmpty()) text = result.text;
                                complete = !interrupted;
                                processingMillis = result.processingMillis;
                                phase = Phase.RESULT;
                                message = text.isEmpty() ? "No speech detected. Please try again."
                                        : job.overloaded ? "Interrupted by processing overload. Buffered words saved; click BOOT to stop the Glyph before retrying."
                                        : interrupted ? "Finalization returned no text. Partial words kept."
                                        : "Saved on this phone. Click BOOT for a new recording.";
                            }
                        }
                        break;
                    }
                }
            }
        } catch (InterruptedException | CancellationException ignored) {
            // pause/disconnect already published the correct state.
        } catch (Exception | LinkageError | OutOfMemoryError e) {
            synchronized (this) {
                if (!closed && !job.cancelled) {
                    interrupted = true;
                    phase = Phase.ERROR;
                    message = e instanceof OutOfMemoryError
                            ? "Not enough free memory. Close other apps and try again."
                            : "Live transcription failed. Words kept. Stop with BOOT, then try again. (" + e.getClass().getSimpleName() + ")";
                }
            }
        } finally {
            synchronized (this) {
                clearQueue(job);
                job.thread = null;
                job.stream = null;
                if (active == job) active = null;
                emit();
            }
            Thread.interrupted();
        }
    }
    @Override public synchronized void onError(String error) {
        if (closed) return;
        if (active != null && active.finished && !active.cancelled) {
            // A valid end frame means all audio is already local. A subsequent
            // socket failure must not cancel the final decode of that recording.
            message = error + " Finalizing the received recording…";
            emit();
            return;
        }
        cancelActive();
        phase = Phase.ERROR;
        message = error;
        emit();
    }
    private void clearQueue(Job job) {
        byte[] chunk;
        while ((chunk = job.queue.poll()) != null) Arrays.fill(chunk, (byte) 0);
        job.queuedBytes = 0;
    }
    private void cancelActive() {
        if (active == null) return;
        if (!complete) interrupted = true;
        active.cancelled = true;
        if (active.stream != null) active.stream.cancel();
        clearQueue(active);
        if (active.thread != null) active.thread.interrupt();
        // Keep active until JNI returns and the worker releases its native stream.
    }
    public synchronized void pause() {
        cancelActive();
        phase = interrupted ? Phase.ERROR : text.isEmpty() ? Phase.IDLE : Phase.RESULT;
        message = "Paused. Words kept on this phone. Reconnect when ready.";
        emit();
    }
    @Override public synchronized void close() {
        if (closed) return;
        pause();
        closed = true;
        observer = state -> {};
        worker.execute(recognizer::close);
        worker.shutdown();
    }
}
