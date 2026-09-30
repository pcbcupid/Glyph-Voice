package com.pcbcupid.voice.speech;

/** Stable output boundary for future consumers; v1 stops at displaying this text. */
public final class TranscriptionResult {
    public final String text;
    public final long processingMillis;
    public TranscriptionResult(String text, long processingMillis) {
        this.text = text;
        this.processingMillis = processingMillis;
    }
}
