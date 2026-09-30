package com.pcbcupid.voice.audio;

import java.io.ByteArrayOutputStream;

/** Legacy batch/test buffer only. The live app uses a bounded queue, not this buffer. */
public final class AudioBuffer {
    private static final int MAX_BATCH_SECONDS = 60;
    public static final int MAX_PACKET_BYTES = 16384;
    private final AudioFormat format;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private int packets;
    public AudioBuffer(AudioFormat format) { this.format = format; }
    public void append(byte[] packet) {
        if (packet.length == 0 || packet.length > MAX_PACKET_BYTES || packet.length % 2 != 0)
            throw new IllegalArgumentException("Invalid PCM packet: use 2–16384 bytes, aligned to 16-bit samples");
        if ((long) bytes.size() + packet.length > (long) format.sampleRate * 2 * MAX_BATCH_SECONDS)
            throw new IllegalArgumentException("Legacy batch buffer too long: maximum 60 seconds");
        bytes.write(packet, 0, packet.length);
        packets++;
    }
    public byte[] finish() {
        if (bytes.size() == 0) throw new IllegalArgumentException("No audio received.");
        if (durationSeconds() < 0.1) throw new IllegalArgumentException("Recording too short. Hold the Glyph button while speaking.");
        return bytes.toByteArray();
    }
    public int size() { return bytes.size(); }
    public int packets() { return packets; }
    public double durationSeconds() { return bytes.size() / (2.0 * format.sampleRate); }
    public AudioFormat format() { return format; }
}
