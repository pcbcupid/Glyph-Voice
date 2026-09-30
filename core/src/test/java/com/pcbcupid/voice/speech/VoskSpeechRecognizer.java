package com.pcbcupid.voice.speech;

import java.io.File;
import java.util.Arrays;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Legacy Vosk host regression fixture only; not included in the Android app. */
public final class VoskSpeechRecognizer implements SpeechRecognizer {
    private Model model;
    public void load(File directory) throws Exception {
        close();
        LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
        model = new Model(directory.getAbsolutePath());
    }
    @Override public TranscriptionResult recognize(byte[] pcm) throws Exception {
        if (model == null) throw new IllegalStateException("Speech model is unavailable");
        long start = System.nanoTime();
        StringBuilder result = new StringBuilder();
        byte[] chunk = new byte[8000];
        try (Recognizer recognizer = new Recognizer(model, 16000.0f)) {
            // Vosk can finalize several utterances inside one button press; retain all of them.
            for (int offset = 0; offset < pcm.length; offset += chunk.length) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                int length = Math.min(chunk.length, pcm.length - offset);
                System.arraycopy(pcm, offset, chunk, 0, length);
                if (recognizer.acceptWaveForm(chunk, length)) append(result, recognizer.getResult());
            }
            append(result, recognizer.getFinalResult());
        } finally { Arrays.fill(chunk, (byte) 0); }
        return new TranscriptionResult(result.toString(), (System.nanoTime() - start) / 1_000_000);
    }
    private static void append(StringBuilder text, String json) throws Exception {
        String part = new JSONObject(json).optString("text", "").trim();
        if (!part.isEmpty()) { if (text.length() > 0) text.append(' '); text.append(part); }
    }
    @Override public void close() { if (model != null) { model.close(); model = null; } }
}
