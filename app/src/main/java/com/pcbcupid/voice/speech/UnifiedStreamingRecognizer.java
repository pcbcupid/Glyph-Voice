package com.pcbcupid.voice.speech;

import com.k2fsa.sherpa.onnx.*;
import com.pcbcupid.voice.audio.AudioFormat;
import com.pcbcupid.voice.audio.PcmFloatDecoder;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Unified RNNT buffered streaming, not repeated full-recording/offline inference. */
public final class UnifiedStreamingRecognizer implements StreamingSpeechRecognizer {
    private OnlineRecognizer recognizer;
    private final android.content.Context context;
    private volatile boolean performanceHintsActive;
    public UnifiedStreamingRecognizer() { this(null); }
    public UnifiedStreamingRecognizer(android.content.Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }
    public boolean performanceHintsActive() { return performanceHintsActive; }
    public void load(File directory) {
        close();
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(new File(directory, "encoder.int8.onnx").getAbsolutePath());
        transducer.setDecoder(new File(directory, "decoder.int8.onnx").getAbsolutePath());
        transducer.setJoiner(new File(directory, "joiner.int8.onnx").getAbsolutePath());
        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(new File(directory, "tokens.txt").getAbsolutePath());
        // Runtime dispatch reads nemo_parakeet_unified_streaming ONNX metadata.
        model.setProvider("cpu");
        model.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
        model.setDebug(false);
        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setModelConfig(model);
        config.setEnableEndpoint(false); // Second BOOT click, not silence, ends the stream.
        config.setDecodingMethod("greedy_search");
        recognizer = new OnlineRecognizer(null, config);
    }
    @Override public Stream openStream(int sampleRate) {
        if (recognizer == null) throw new IllegalStateException("Speech model is unavailable");
        new AudioFormat(sampleRate, 1, "pcm_s16le");
        return new LiveStream(recognizer, sampleRate);
    }
    private final class LiveStream implements Stream {
        private final OnlineRecognizer engine;
        private final int rate;
        private OnlineStream stream;
        private boolean finished;
        private long inferenceNanos;
        private String partial = "";
        private final InferencePerformanceHints hints;
        LiveStream(OnlineRecognizer engine, int rate) {
            this.engine = engine;
            this.rate = rate;
            stream = engine.createStream("");
            hints = new InferencePerformanceHints(context);
            performanceHintsActive = hints.active();
        }
        @Override public String accept(byte[] pcm) {
            if (finished || stream == null) throw new IllegalStateException("Stream already finished");
            checkCancelled();
            long workStarted = System.nanoTime();
            float[] samples = PcmFloatDecoder.decode(pcm);
            try {
                // The native stream owns a persistent band-limited resampler and
                // feature history. Do not resample Wi-Fi packets independently.
                stream.acceptWaveform(samples, rate);
            } finally { Arrays.fill(samples, 0); }
            // Rebuilding the whole transcript on every 20 ms packet becomes
            // expensive for long sessions. It changes only after a decode.
            boolean decoded = drainReady();
            if (decoded) partial = engine.getResult(stream).getText().trim();
            hints.report(pcm.length / 2, rate, System.nanoTime() - workStarted, decoded);
            performanceHintsActive = hints.active();
            return partial;
        }
        private boolean drainReady() {
            boolean decoded = false;
            while (engine.isReady(stream)) {
                checkCancelled();
                long begin = System.nanoTime();
                engine.decode(stream);
                decoded = true;
                inferenceNanos += System.nanoTime() - begin;
            }
            checkCancelled();
            return decoded;
        }
        @Override public TranscriptionResult finish() {
            if (finished || stream == null) throw new IllegalStateException("Stream already finished");
            checkCancelled();
            finished = true;
            // Flush resampler/feature tails. Unified InputFinished pads missing
            // right context itself, including the final short center chunk.
            stream.acceptWaveform(new float[rate / 50], rate);
            stream.inputFinished();
            drainReady();
            return new TranscriptionResult(engine.getResult(stream).getText().trim(), inferenceNanos / 1_000_000);
        }
        @Override public void close() {
            hints.close(); performanceHintsActive = false;
            if (stream != null) { stream.release(); stream = null; }
        }
    }
    /** File/test convenience; production reception uses openStream/accept/finish. */
    @Override public TranscriptionResult recognize(byte[] pcm) throws Exception {
        try (Stream stream = openStream(16000)) {
            for (int offset = 0; offset < pcm.length; offset += 3200) {
                byte[] part = Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + 3200));
                try { stream.accept(part); } finally { Arrays.fill(part, (byte) 0); }
            }
            return stream.finish();
        }
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    @Override public void close() {
        if (recognizer != null) { recognizer.release(); recognizer = null; }
    }
}
