package com.pcbcupid.voice.speech;

import com.pcbcupid.voice.audio.*;
import com.pcbcupid.voice.core.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.Random;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okio.ByteString;
import com.pcbcupid.voice.network.WebSocketAudioReceiver;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Optional real-engine tests: -PvoiceFixtures=/path/containing/model.zip/and/test.wav */
public class LocalInferenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void localWavToRealEnglishTextAndSilence() throws Exception {
        String fixtures = System.getProperty("voiceFixtures");
        Assume.assumeNotNull(fixtures);
        LegacyVoskModelManager manager = new LegacyVoskModelManager(temp.newFolder());
        manager.install(new FileInputStream(new File(fixtures, "model.zip")));
        assertTrue(manager.isInstalled());
        VoskSpeechRecognizer recognizer = new VoskSpeechRecognizer();
        recognizer.load(manager.modelDirectory());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch done = new CountDownLatch(1);
        try (TranscriptionSession session = new TranscriptionSession(recognizer, new PcmProcessor(), executor);
             WavFileAudioReceiver source = new WavFileAudioReceiver(() -> new FileInputStream(new File(fixtures, "test.wav")))) {
            session.setReady(true);
            session.observe(state -> { if (state.phase == VoiceState.Phase.RESULT || state.phase == VoiceState.Phase.ERROR) done.countDown(); });
            source.connect("", session);
            assertTrue("Inference timed out", done.await(60, TimeUnit.SECONDS));
            VoiceState state = session.snapshot();
            assertEquals(state.message, VoiceState.Phase.RESULT, state.phase);
            assertTrue("Expected spoken digits from official Vosk sample", state.text.contains("one") && state.text.contains("zero"));
            // Entire recognition path has no network dependency. Recognizer is now idle.
            TranscriptionResult silence = executor.submit(() -> recognizer.recognize(new byte[32000])).get(30, TimeUnit.SECONDS);
            assertEquals("", silence.text);
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
    private byte[] readPcm(File file) throws Exception {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        BlockingQueue<String> finished = new LinkedBlockingQueue<>();
        try (WavFileAudioReceiver source = new WavFileAudioReceiver(() -> new FileInputStream(file))) {
            source.connect("", new AudioReceiver.Listener() {
                public void onConnection(AudioReceiver.Connection state) {}
                public void onStart(String id, AudioFormat format) { assertEquals(16000, format.sampleRate); }
                public void onAudio(byte[] bytes) { pcm.write(bytes, 0, bytes.length); }
                public void onEnd(String id) { finished.add("ok"); }
                public void onError(String error) { finished.add(error); }
            });
            assertEquals("ok", finished.poll(10, TimeUnit.SECONDS));
        }
        return pcm.toByteArray();
    }
    private VoskSpeechRecognizer installedRecognizer(String fixtures) throws Exception {
        LegacyVoskModelManager manager = new LegacyVoskModelManager(temp.newFolder());
        manager.install(new FileInputStream(new File(fixtures, "model.zip")));
        VoskSpeechRecognizer recognizer = new VoskSpeechRecognizer();
        recognizer.load(manager.modelDirectory());
        return recognizer;
    }
    @Test public void normalEnglishLongSpeechNoiseAndSpeedVariants() throws Exception {
        String fixtures = System.getProperty("voiceFixtures");
        Assume.assumeNotNull(fixtures);
        Assume.assumeTrue(new File(fixtures, "speech.wav").isFile());
        byte[] pcm = readPcm(new File(fixtures, "speech.wav"));
        try (VoskSpeechRecognizer recognizer = installedRecognizer(fixtures)) {
            String normal = recognizer.recognize(pcm).text;
            assertTrue(normal, normal.contains("country") && normal.contains("ask"));
            byte[] longSpeech = new byte[pcm.length * 3];
            for (int i = 0; i < 3; i++) System.arraycopy(pcm, 0, longSpeech, pcm.length * i, pcm.length);
            String lengthy = recognizer.recognize(longSpeech).text;
            assertTrue("Retain intermediate Vosk utterances", lengthy.length() > normal.length() * 2);
            byte[] noisy = pcm.clone();
            Random random = new Random(42);
            for (int i = 0; i < noisy.length; i += 2) {
                int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                sample = Math.max(-32768, Math.min(32767, sample + random.nextInt(401) - 200));
                noisy[i] = (byte) sample; noisy[i + 1] = (byte) (sample >> 8);
            }
            assertFalse("Mild background noise", recognizer.recognize(noisy).text.isEmpty());
            for (double speed : new double[]{0.8, 1.25}) {
                byte[] varied = new byte[(int) (pcm.length / 2 / speed) * 2];
                for (int i = 0; i < varied.length / 2; i++) {
                    int source = Math.min(pcm.length / 2 - 1, (int) (i * speed));
                    varied[2 * i] = pcm[2 * source]; varied[2 * i + 1] = pcm[2 * source + 1];
                }
                assertFalse("Speed " + speed, recognizer.recognize(varied).text.isEmpty());
            }
        }
    }
    @Test public void websocketToRealRecognition() throws Exception {
        Assume.assumeFalse("Socket test runs separately from isolated offline inference", Boolean.getBoolean("voiceOffline"));
        String fixtures = System.getProperty("voiceFixtures");
        Assume.assumeNotNull(fixtures);
        byte[] pcm = readPcm(new File(fixtures, "test.wav"));
        VoskSpeechRecognizer recognizer = installedRecognizer(fixtures);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch done = new CountDownLatch(1);
        try (MockWebServer server = new MockWebServer();
             TranscriptionSession session = new TranscriptionSession(recognizer, new PcmProcessor(), executor)) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    ws.send("{\"type\":\"start\",\"version\":1,\"id\":\"speech\",\"sampleRate\":16000,\"channels\":1,\"encoding\":\"pcm_s16le\"}");
                    for (int offset = 0; offset < pcm.length; offset += 4096)
                        ws.send(ByteString.of(pcm, offset, Math.min(4096, pcm.length - offset)));
                    ws.send("{\"type\":\"end\",\"id\":\"speech\",\"bytes\":" + pcm.length + "}");
                }
            }));
            server.start();
            session.setReady(true);
            session.observe(state -> { if (state.phase == VoiceState.Phase.RESULT || state.phase == VoiceState.Phase.ERROR) done.countDown(); });
            try (WebSocketAudioReceiver receiver = new WebSocketAudioReceiver(() -> new OkHttpClient.Builder()
                    .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().url(server.url("/audio")).build())).build())) {
                receiver.connect("192.168.1.5", session);
                assertTrue(done.await(60, TimeUnit.SECONDS));
                assertEquals(session.snapshot().message, VoiceState.Phase.RESULT, session.snapshot().phase);
                assertTrue(session.snapshot().text.contains("zero"));
            }
        }
    }
}
