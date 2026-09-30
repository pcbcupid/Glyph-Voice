package com.pcbcupid.voice.audio;

public interface AudioProcessor {
    /** Returns mono signed PCM16 little-endian at 16 kHz. */
    byte[] toModelPcm(byte[] source, AudioFormat format);
}
