package com.pcbcupid.voice.core;

import com.pcbcupid.voice.audio.AudioReceiver.Connection;

/** Immutable UI snapshot; audio and packet parsing never reach the UI. */
public final class VoiceState {
    public enum Phase { IDLE, RECEIVING, PROCESSING, RESULT, ERROR }
    public final Connection connection;
    public final Phase phase;
    public final String text, message;
    public final int sampleRate;
    public final long packets, bytes;
    public final double duration;
    public final long processingMillis;
    public final long recordingSequence;
    public final boolean complete, interrupted;
    public VoiceState(Connection connection, Phase phase, String text, String message,
                      long packets, long bytes, int sampleRate, double duration, long processingMillis) {
        this(connection, phase, text, message, packets, bytes, sampleRate, duration,
                processingMillis, 0, phase == Phase.RESULT, phase == Phase.ERROR);
    }
    public VoiceState(Connection connection, Phase phase, String text, String message,
                      long packets, long bytes, int sampleRate, double duration, long processingMillis,
                      long recordingSequence, boolean complete, boolean interrupted) {
        this.connection = connection;
        this.phase = phase;
        this.text = text;
        this.message = message;
        this.packets = packets;
        this.bytes = bytes;
        this.sampleRate = sampleRate;
        this.duration = duration;
        this.processingMillis = processingMillis;
        this.recordingSequence = recordingSequence;
        this.complete = complete;
        this.interrupted = interrupted;
    }
}
