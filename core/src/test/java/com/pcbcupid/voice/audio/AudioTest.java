package com.pcbcupid.voice.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioTest {
    @Test public void pcmPassThroughIsExact() {
        byte[] data = {0, -128, -1, 127, 0, 0};
        assertArrayEquals(data, new PcmProcessor().toModelPcm(data, AudioFormat.standard()));
    }
    @Test public void supportedRatesHaveCorrectDurationAndDcLevel() {
        for (int rate : new int[]{8000, 16000, 32000, 44100, 48000}) {
            byte[] input = new byte[rate * 2];
            for (int i = 0; i < rate; i++) { input[2 * i] = (byte) 0xe8; input[2 * i + 1] = 3; }
            byte[] output = new PcmProcessor().toModelPcm(input, new AudioFormat(rate, 1, "pcm_s16le"));
            assertEquals(32000, output.length);
            for (int i = 0; i < output.length; i += 2)
                assertEquals(1000, (short) ((output[i] & 255) | (output[i + 1] << 8)));
        }
    }
    @Test public void downsamplingSuppressesAboveNyquistTone() {
        byte[] input = tone(48000, 12000);
        byte[] output = new PcmProcessor().toModelPcm(input, new AudioFormat(48000, 1, "pcm_s16le"));
        double energy = 0;
        for (int i = 200; i < output.length - 200; i += 2) {
            int sample = (short) ((output[i] & 255) | (output[i + 1] << 8));
            energy += sample * sample;
        }
        assertTrue(Math.sqrt(energy / (output.length / 2 - 200)) < 100);
    }
    private static byte[] tone(int rate, int hz) {
        byte[] bytes = new byte[rate * 2];
        for (int i = 0; i < rate; i++) {
            short value = (short) (10000 * Math.sin(2 * Math.PI * hz * i / rate));
            bytes[i * 2] = (byte) value; bytes[i * 2 + 1] = (byte) (value >> 8);
        }
        return bytes;
    }
    @Test public void rejectsUnsupportedFormatsAndRates() {
        assertThrows(IllegalArgumentException.class, () -> new AudioFormat(22050, 1, "pcm_s16le"));
        assertThrows(IllegalArgumentException.class, () -> new AudioFormat(16000, 2, "pcm_s16le"));
        assertThrows(IllegalArgumentException.class, () -> new AudioFormat(16000, 1, "mp3"));
    }
    @Test public void rejectsEmptyShortAndCorruptPcm() {
        AudioBuffer buffer = new AudioBuffer(AudioFormat.standard());
        assertThrows(IllegalArgumentException.class, buffer::finish);
        assertThrows(IllegalArgumentException.class, () -> buffer.append(new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(new byte[16386]));
        buffer.append(new byte[100]);
        assertThrows(IllegalArgumentException.class, buffer::finish);
        assertThrows(IllegalArgumentException.class, () -> new PcmProcessor().toModelPcm(new byte[1], AudioFormat.standard()));
    }
    @Test public void sixtySecondLimitIsExact() {
        AudioBuffer buffer = new AudioBuffer(AudioFormat.standard());
        for (int i = 0; i < 600; i++) buffer.append(new byte[3200]);
        assertEquals(60, buffer.durationSeconds(), 0);
        assertEquals(1920000, buffer.finish().length);
        assertThrows(IllegalArgumentException.class, () -> buffer.append(new byte[2]));
    }
}
