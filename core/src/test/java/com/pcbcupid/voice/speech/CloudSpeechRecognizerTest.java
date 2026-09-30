package com.pcbcupid.voice.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.*;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CloudSpeechRecognizerTest {
    private CloudSpeechRecognizer client(MockWebServer server) {
        return new CloudSpeechRecognizer(new OkHttpClient.Builder(), server.url("/v1/audio/transcriptions"), "speech-model", "test-key");
    }
    @Test public void chunksAudioInOrderAndFlushesTheShortTailOnlyOnce() throws Exception {
        try (MockWebServer server = new MockWebServer(); CloudSpeechRecognizer recognizer = client(server);
             StreamingSpeechRecognizer.Stream stream = recognizer.openStream(16000)) {
            server.enqueue(new MockResponse().setBody("{\"text\":\"first words\"}"));
            server.enqueue(new MockResponse().setBody("{\"text\":\"last words\"}"));
            assertEquals("", stream.accept(new byte[32000]));
            assertEquals(0, server.getRequestCount());
            assertEquals("first words", stream.accept(new byte[14 * 32000]));
            stream.accept(new byte[640]);
            assertEquals("first words last words", stream.finish().text);
            assertEquals(2, server.getRequestCount());
            RecordedRequest first = server.takeRequest();
            assertEquals("/v1/audio/transcriptions", first.getPath());
            assertEquals("Bearer test-key", first.getHeader("Authorization"));
            String body = first.getBody().readUtf8();
            assertTrue(body.contains("name=\"model\"")); assertTrue(body.contains("speech-model"));
            assertTrue(body.contains("name=\"file\"; filename=\"speech.wav\""));
            assertTrue(body.contains("Content-Type: audio/wav"));
            assertTrue(body.contains("RIFF"));
            try { stream.finish(); fail("Duplicate finish"); } catch (IllegalStateException expected) { }
        }
    }
    @Test public void wavCarriesExactPcmRateAndLength() {
        byte[] pcm = {1, 2, 3, 4};
        byte[] wav = CloudSpeechRecognizer.wav(pcm, pcm.length, 48000);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(40, header.getInt(4)); assertEquals(48000, header.getInt(24));
        assertEquals(4, header.getInt(40)); assertArrayEquals(pcm, java.util.Arrays.copyOfRange(wav, 44, wav.length));
    }
    @Test public void refusesInsecureAndCredentialBearingEndpoints() {
        for (String url : new String[]{"http://example.com/stt", "https://user:key@example.com/stt", "https://example.com/stt?key=secret", "https://example.com/stt#x", "nonsense"}) {
            try { CloudSpeechRecognizer.validateEndpoint(url); fail(url); } catch (IllegalArgumentException expected) { }
        }
        assertEquals("https://example.com/v1/audio/transcriptions", CloudSpeechRecognizer.validateEndpoint("https://example.com/v1/audio/transcriptions").toString());
    }
    @Test public void redirectsErrorsAndMalformedResponsesNeverBecomeTranscripts() throws Exception {
        for (MockResponse response : new MockResponse[]{
                new MockResponse().setResponseCode(302).addHeader("Location", "https://example.com/leak"),
                new MockResponse().setResponseCode(401).setBody("secret provider body"),
                new MockResponse().setBody("{\"text\":null}"),
                new MockResponse().setBody("x".repeat(65537))}) {
            try (MockWebServer server = new MockWebServer(); CloudSpeechRecognizer recognizer = client(server);
                 StreamingSpeechRecognizer.Stream stream = recognizer.openStream(16000)) {
                server.enqueue(response); stream.accept(new byte[3200]);
                try { stream.finish(); fail("Accepted bad response"); }
                catch (Exception expected) { assertFalse(expected.getMessage().contains("secret provider body")); }
                assertEquals(1, server.getRequestCount());
            }
        }
    }
    @Test public void cancelAbortsAnInFlightUpload() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); CloudSpeechRecognizer recognizer = client(server);
             StreamingSpeechRecognizer.Stream stream = recognizer.openStream(16000)) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            stream.accept(new byte[3200]);
            Future<?> work = worker.submit(() -> { try { stream.finish(); fail("Cancelled request succeeded"); } catch (Exception expected) { } });
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
            stream.cancel(); work.get(3, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
}
