package com.pcbcupid.voice.audio;

/** PCM decoding boundary; no packet-local resampling, normalization or gain. */
public final class PcmFloatDecoder {
    private PcmFloatDecoder() { }
    public static float[] decode(byte[] pcm) {
        if (pcm.length % 2 != 0) throw new IllegalArgumentException("Unaligned PCM16 audio");
        float[] samples = new float[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            short sample = (short) ((pcm[2 * i] & 255) | (pcm[2 * i + 1] << 8));
            samples[i] = sample / 32768.0f;
        }
        return samples;
    }
}
