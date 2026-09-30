package com.pcbcupid.voice.core;

import com.pcbcupid.voice.audio.*;
import com.pcbcupid.voice.speech.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.pcbcupid.voice.core.VoiceState.Phase;

/** One capture/inference at a time; bounded RAM and no deferred recording queue. */
public final class TranscriptionSession implements AudioReceiver.Listener, AutoCloseable {
    private final SpeechRecognizer recognizer;
    private final AudioProcessor processor;
    private final ExecutorService worker;
    private Consumer<VoiceState> observer = state -> {};
    private AudioReceiver.Connection connection = AudioReceiver.Connection.DISCONNECTED;
    private Phase phase = Phase.IDLE;
    private String text = "", message = "Connect your Glyph to begin.", id;
    private AudioBuffer buffer;
    private boolean ready, busy, closed;
    private long epoch, processingMillis;
    private Thread processingThread;
    private int packets, bytes, sampleRate;
    private double duration;

    public TranscriptionSession(SpeechRecognizer recognizer, AudioProcessor processor, ExecutorService worker) {
        this.recognizer = recognizer;
        this.processor = processor;
        this.worker = worker;
    }
    public synchronized void observe(Consumer<VoiceState> observer) { this.observer = observer; emit(); }
    public synchronized void setReady(boolean ready) { this.ready = ready; emit(); }
    public synchronized boolean isBusy() { return busy || buffer != null; }
    public synchronized VoiceState snapshot() {
        return new VoiceState(connection, phase, text, message, packets, bytes, sampleRate, duration, processingMillis);
    }
    private void emit() { if (!closed) observer.accept(snapshot()); }
    @Override public synchronized void onConnection(AudioReceiver.Connection state) {
        connection = state;
        if (state == AudioReceiver.Connection.CONNECTED && phase == Phase.ERROR && !busy) {
            phase = text.isEmpty() ? Phase.IDLE : Phase.RESULT;
            message = "Hold the Glyph button and speak.";
        }
        if (state != AudioReceiver.Connection.CONNECTED && buffer != null) {
            buffer = null;
            id = null;
            phase = Phase.ERROR;
            message = "Connection lost. Incomplete recording discarded.";
        } else if (phase == Phase.IDLE) {
            message = state == AudioReceiver.Connection.CONNECTED ? "Hold the Glyph button and speak." : "Connect your Glyph to begin.";
        }
        emit();
    }
    @Override public synchronized void onStart(String id, AudioFormat format) {
        if (closed) return;
        if (busy) { message = "Still processing. Please wait before recording again."; emit(); return; }
        if (!ready) { onError("Speech model is not ready. Wait for English speech recognition to finish preparing."); return; }
        this.id = id;
        buffer = new AudioBuffer(format);
        phase = Phase.RECEIVING;
        message = "Listening… Release the Glyph button when finished.";
        packets = bytes = 0;
        duration = 0;
        processingMillis = 0;
        sampleRate = format.sampleRate;
        emit();
    }
    @Override public synchronized void onAudio(byte[] pcm) {
        if (buffer == null || closed) return;
        try {
            buffer.append(pcm);
            packets = buffer.packets(); bytes = buffer.size(); duration = buffer.durationSeconds();
            // A few snapshots per second, instead of waking the UI for every 20 ms packet.
            if (packets == 1 || packets % 10 == 0) emit();
        } catch (IllegalArgumentException e) { onError(e.getMessage()); }
    }
    @Override public synchronized void onEnd(String id) {
        if (buffer == null || closed) return;
        if (!id.equals(this.id)) { onError("Recording id mismatch"); return; }
        final byte[] raw;
        final AudioFormat format = buffer.format();
        try { raw = buffer.finish(); }
        catch (IllegalArgumentException e) { onError(e.getMessage()); return; }
        buffer = null;
        this.id = null;
        busy = true;
        phase = Phase.PROCESSING;
        message = "Processing speech on your phone…";
        long token = epoch;
        emit();
        worker.submit(() -> {
            byte[] converted = null;
            try {
                synchronized (this) {
                    if (closed || token != epoch) return;
                    processingThread = Thread.currentThread();
                }
                converted = processor.toModelPcm(raw, format);
                TranscriptionResult result = recognizer.recognize(converted);
                synchronized (this) {
                    if (!closed && token == epoch) {
                        text = result.text;
                        processingMillis = result.processingMillis;
                        phase = Phase.RESULT;
                        message = text.isEmpty() ? "No speech detected. Please try again." : "Ready for your next recording.";
                    }
                }
            } catch (OutOfMemoryError e) {
                synchronized (this) {
                    if (!closed && token == epoch) {
                        phase = Phase.ERROR;
                        message = "Not enough free memory. Close other apps and try a shorter recording.";
                    }
                }
            } catch (Exception | LinkageError e) {
                synchronized (this) {
                    if (!closed && token == epoch) {
                        phase = Phase.ERROR;
                        message = "Couldn't transcribe the recording. Please try again. (" + e.getClass().getSimpleName() + ")";
                    }
                }
            } finally {
                Arrays.fill(raw, (byte) 0);
                if (converted != null && converted != raw) Arrays.fill(converted, (byte) 0);
                synchronized (this) { processingThread = null; busy = false; emit(); }
                Thread.interrupted();
            }
        });
    }
    @Override public synchronized void onError(String message) {
        buffer = null;
        id = null;
        if (!busy) phase = Phase.ERROR;
        this.message = message;
        emit();
    }
    /** Called when the app leaves the foreground. Stale inference cannot overwrite new UI. */
    public synchronized void pause() {
        epoch++;
        buffer = null;
        id = null;
        if (processingThread != null) processingThread.interrupt();
        phase = text.isEmpty() ? Phase.IDLE : Phase.RESULT;
        message = "Recording paused. Reconnect your Glyph when ready.";
        emit();
    }
    @Override public synchronized void close() {
        pause();
        closed = true;
        observer = state -> {};
        // Serialize native model disposal after any running inference/import.
        worker.execute(recognizer::close);
        worker.shutdown();
    }
}
