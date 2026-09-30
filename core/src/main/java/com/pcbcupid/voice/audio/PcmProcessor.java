package com.pcbcupid.voice.audio;

/** Windowed-sinc resampling, including low-pass filtering before downsampling. */
public final class PcmProcessor implements AudioProcessor {
    private static final int TARGET_RATE = 16000;
    private static final int RADIUS = 32;
    @Override public byte[] toModelPcm(byte[] source, AudioFormat format) {
        if (source.length == 0 || source.length % 2 != 0)
            throw new IllegalArgumentException("Empty or truncated PCM16 audio");
        if (format.sampleRate == TARGET_RATE) return source;
        int samples = source.length / 2;
        int outputSamples = (int) ((long) samples * TARGET_RATE / format.sampleRate);
        byte[] output = new byte[outputSamples * 2];
        double cutoff = 0.94 * Math.min(1.0, (double) TARGET_RATE / format.sampleRate);
        for (int i = 0; i < outputSamples; i++) {
            if ((i & 4095) == 0 && Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            double position = (double) i * format.sampleRate / TARGET_RATE;
            int center = (int) position;
            double sum = 0, weights = 0;
            for (int j = center - RADIUS + 1; j <= center + RADIUS; j++) {
                double distance = position - j;
                if (Math.abs(distance) >= RADIUS) continue;
                double x = Math.PI * cutoff * distance;
                double weight = cutoff * (Math.abs(x) < 1e-10 ? 1 : Math.sin(x) / x)
                        * (0.5 + 0.5 * Math.cos(Math.PI * distance / RADIUS));
                int clamped = Math.max(0, Math.min(samples - 1, j));
                int sample = (short) ((source[2 * clamped] & 255) | (source[2 * clamped + 1] << 8));
                sum += sample * weight;
                weights += weight;
            }
            int sample = (int) Math.max(-32768, Math.min(32767, Math.round(sum / weights)));
            output[i * 2] = (byte) sample;
            output[i * 2 + 1] = (byte) (sample >> 8);
        }
        return output;
    }
}
