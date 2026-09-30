package com.pcbcupid.voice.speech;

import android.content.Context;
import java.io.File;

/** Configured only on the inference worker while no recording is active. */
public final class SpeechEngine implements StreamingSpeechRecognizer {
    private final UnifiedStreamingRecognizer local;
    private StreamingSpeechRecognizer active;
    public SpeechEngine(Context context) { local = new UnifiedStreamingRecognizer(context); active = local; }
    public void load(File directory) { close(); local.load(directory); active = local; }
    public void loadCloud(SpeechSettings settings) throws Exception {
        close(); active = new CloudSpeechRecognizer(settings.endpoint(), settings.model(), settings.key());
    }
    public boolean performanceHintsActive() { return active == local && local.performanceHintsActive(); }
    @Override public Stream openStream(int rate) throws Exception { return active.openStream(rate); }
    @Override public TranscriptionResult recognize(byte[] pcm) throws Exception { return active.recognize(pcm); }
    @Override public void close() { active.close(); }
}
