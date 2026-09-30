package com.pcbcupid.voice.audio;

import java.io.*;
import java.nio.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WavTest {
    private static byte[] wav(int bits, int declaredBytes, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + declaredBytes).put("WAVEfmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000)
                .putInt(32000).putShort((short) 2).putShort((short) bits)
                .put("data".getBytes()).putInt(declaredBytes).put(data);
        return b.array();
    }
    private static String replay(byte[] wav) throws Exception {
        BlockingQueue<String> result = new LinkedBlockingQueue<>();
        try (WavFileAudioReceiver receiver = new WavFileAudioReceiver(() -> new ByteArrayInputStream(wav))) {
            receiver.connect("", new AudioReceiver.Listener() {
                public void onConnection(AudioReceiver.Connection state) {}
                public void onStart(String id, AudioFormat format) {}
                public void onAudio(byte[] pcm) {}
                public void onEnd(String id) { result.add("END"); }
                public void onError(String message) { result.add(message); }
            });
            return result.poll(2, TimeUnit.SECONDS);
        }
    }
    @Test public void validWavIsDelivered() throws Exception { assertEquals("END", replay(wav(16, 3200, new byte[3200]))); }
    @Test public void longWavIsStreamedWithoutDurationOrEightMegabyteCap() throws Exception {
        byte[] pcm = new byte[32000 * 300];
        assertEquals("END", replay(wav(16, pcm.length, pcm)));
    }
    @Test public void invalidHeaderIsRejected() throws Exception { assertTrue(replay(new byte[44]).contains("RIFF")); }
    @Test public void unsupportedWavEncodingIsRejected() throws Exception { assertTrue(replay(wav(8, 3200, new byte[3200])).contains("16-bit")); }
    @Test public void truncatedDataNeverFinalizes() throws Exception { assertTrue(replay(wav(16, 3200, new byte[100])).contains("Truncated")); }
}
