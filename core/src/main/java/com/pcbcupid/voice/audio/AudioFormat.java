package com.pcbcupid.voice.audio;

/** The wire contract: signed little-endian PCM16, mono. */
public final class AudioFormat {
    public final int sampleRate;
    public AudioFormat(int sampleRate, int channels, String encoding) {
        if (channels != 1 || !"pcm_s16le".equals(encoding))
            throw new IllegalArgumentException("Unsupported format: expected mono pcm_s16le");
        if (sampleRate != 8000 && sampleRate != 16000 && sampleRate != 32000 &&
                sampleRate != 44100 && sampleRate != 48000)
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        this.sampleRate = sampleRate;
    }
    public static AudioFormat standard() { return new AudioFormat(16000, 1, "pcm_s16le"); }
}
