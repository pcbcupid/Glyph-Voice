package com.pcbcupid.voice.network;

import com.pcbcupid.voice.audio.*;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okio.ByteString;
import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkTest {
    private static final String START = "{\"type\":\"start\",\"version\":1,\"id\":\"r1\",\"sampleRate\":16000,\"channels\":1,\"encoding\":\"pcm_s16le\"}";
    private static final class Events implements AudioReceiver.Listener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        public void onConnection(AudioReceiver.Connection state) { events.add(state.name()); }
        public void onStart(String id, AudioFormat format) { events.add("START"); }
        public void onAudio(byte[] data) { events.add("AUDIO:" + data.length); }
        public void onEnd(String id) { events.add("END"); }
        public void onError(String error) { events.add("ERROR:" + error); }
        public void onWaitingForAudio(String warning) { events.add("WAITING"); }
        String next() throws Exception { return events.poll(5, TimeUnit.SECONDS); }
    }
    private static WebSocketAudioReceiver receiver(MockWebServer server) {
        return new WebSocketAudioReceiver(() -> new OkHttpClient.Builder()
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().url(server.url("/audio")).build()))
                .build());
    }
    @Test public void manualDiscoveryRejectsPublicDnsAndMalformedEndpoints() {
        assertEquals("ws://192.168.1.5:8080/audio", LocalEndpoint.url("192.168.1.5"));
        assertEquals("ws://10.0.2.2:9000/audio", LocalEndpoint.url("10.0.2.2:9000"));
        for (String value : new String[]{"example.com", "8.8.8.8", "127.0.0.1", "192.168.1.256", "192.168.1.1:0", "192.168.1.1:65536", "ws://192.168.1.1", "192.168.01.1", "172.32.0.1", "192.168.1.1/path"})
            assertThrows(value, IllegalArgumentException.class, () -> LocalEndpoint.url(value));
    }
    @Test public void phoneStopWaitsForFirmwareTailAndMatchingEnd() throws Exception {
        BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    ws.send(START.replace("\"version\":1", "\"version\":1,\"control\":\"stop-v1\""));
                    ws.send(ByteString.of(new byte[3200]));
                }
                @Override public void onMessage(WebSocket ws, String command) {
                    commands.add(command);
                    ws.send(ByteString.of(new byte[640]));
                    ws.send("{\"type\":\"end\",\"id\":\"r1\",\"bytes\":3840}");
                }
            }));
            server.start();
            try (WebSocketAudioReceiver receiver = receiver(server)) {
                Events events = new Events(); receiver.connect("192.168.1.5", events);
                assertEquals("CONNECTING", events.next()); assertEquals("CONNECTED", events.next());
                assertEquals("START", events.next()); assertEquals("AUDIO:3200", events.next());
                assertEquals(AudioReceiver.StopResult.SENT, receiver.requestStop());
                assertEquals("STOP r1", commands.poll(3, TimeUnit.SECONDS));
                assertEquals("AUDIO:640", events.next()); assertEquals("END", events.next());
                assertEquals(AudioReceiver.StopResult.NOT_RECORDING, receiver.requestStop());
            }
        }
    }
    @Test public void audioPauseWarnsWithoutDisconnectAndStillAcceptsStopAndTail() throws Exception {
        BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    ws.send(START.replace("\"version\":1", "\"version\":1,\"control\":\"stop-v1\""));
                    ws.send(ByteString.of(new byte[3200]));
                }
                @Override public void onMessage(WebSocket ws, String command) {
                    commands.add(command);
                    ws.send(ByteString.of(new byte[3200]));
                    ws.send("{\"type\":\"end\",\"id\":\"r1\",\"bytes\":6400}");
                }
            }));
            server.start();
            try (WebSocketAudioReceiver receiver = receiver(server)) {
                Events events = new Events(); receiver.connect("192.168.1.5", events);
                assertEquals("CONNECTING", events.next()); assertEquals("CONNECTED", events.next());
                assertEquals("START", events.next()); assertEquals("AUDIO:3200", events.next());
                assertEquals("WAITING", events.events.poll(7, TimeUnit.SECONDS));
                assertNull(events.events.poll(100, TimeUnit.MILLISECONDS));
                assertEquals(AudioReceiver.StopResult.SENT, receiver.requestStop());
                assertEquals("STOP r1", commands.poll(3, TimeUnit.SECONDS));
                assertEquals("AUDIO:3200", events.next()); assertEquals("END", events.next());
                assertEquals(1, server.getRequestCount());
            }
        }
    }
    @Test public void oldFirmwareIsNotSentAnUnsupportedStopCommand() throws Exception {
        BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) { ws.send(START); }
                @Override public void onMessage(WebSocket ws, String command) { commands.add(command); }
            }));
            server.start();
            try (WebSocketAudioReceiver receiver = receiver(server)) {
                Events events = new Events(); receiver.connect("192.168.1.5", events);
                assertEquals("CONNECTING", events.next()); assertEquals("CONNECTED", events.next());
                assertEquals("START", events.next());
                assertEquals(AudioReceiver.StopResult.UNSUPPORTED, receiver.requestStop());
                assertNull(commands.poll(100, TimeUnit.MILLISECONDS));
            }
        }
    }
    @Test public void connectsAndDeliversOrderedRecording() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    ws.send(START); ws.send(ByteString.of(new byte[3200]));
                    ws.send("{\"type\":\"end\",\"id\":\"r1\",\"bytes\":3200}");
                }
            }));
            server.start();
            try (WebSocketAudioReceiver receiver = receiver(server)) {
                Events events = new Events();
                receiver.connect("192.168.1.5", events);
                assertEquals("CONNECTING", events.next()); assertEquals("CONNECTED", events.next());
                assertEquals("START", events.next()); assertEquals("AUDIO:3200", events.next()); assertEquals("END", events.next());
                receiver.disconnect(); assertEquals("DISCONNECTED", events.next());
                assertNull(events.events.poll(1200, TimeUnit.MILLISECONDS));
            }
        }
    }
    @Test public void malformedPacketsReconnectWithoutCompletingAudio() throws Exception {
        for (String malformed : new String[]{"{", "{\"type\":\"unknown\"}", START.replace("16000", "12345"), START.replace("16000", "16000.5"),
                "{\"type\":\"end\",\"id\":\"other\",\"bytes\":0}"}) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override public void onOpen(WebSocket ws, Response response) { ws.send(malformed); }
                }));
                server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {}));
                server.start();
                try (WebSocketAudioReceiver receiver = receiver(server)) {
                    Events events = new Events(); receiver.connect("192.168.1.5", events);
                    assertEquals("CONNECTING", events.next()); assertEquals("CONNECTED", events.next());
                    assertTrue(events.next().startsWith("ERROR:"));
                    assertEquals("RECONNECTING", events.next()); assertEquals("RECONNECTING", events.next());
                    assertEquals("CONNECTED", events.next());
                }
            }
        }
    }
    @Test public void acceptsThreeMinutesOfAudioAndExactEndCount() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    ws.send(START);
                    for (int i = 0; i < 360; i++) ws.send(ByteString.of(new byte[16000]));
                    ws.send("{\"type\":\"end\",\"id\":\"r1\",\"bytes\":5760000}");
                }
            }));
            server.start();
            try (WebSocketAudioReceiver receiver = receiver(server)) {
                Events events = new Events();
                receiver.connect("192.168.1.5", events);
                assertEquals("CONNECTING", events.next());
                assertEquals("CONNECTED", events.next());
                assertEquals("START", events.next());
                for (int i = 0; i < 360; i++) assertEquals("AUDIO:16000", events.next());
                assertEquals("END", events.next());
            }
        }
    }
    @Test public void unexpectedDisconnectReconnects() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) { ws.close(1000, "reboot"); }
            }));
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {}));
            server.start();
            try (WebSocketAudioReceiver receiver = receiver(server)) {
                Events events = new Events(); receiver.connect("192.168.1.5", events);
                assertEquals("CONNECTING", events.next()); assertEquals("CONNECTED", events.next());
                assertTrue(events.next().startsWith("ERROR:"));
                assertEquals("RECONNECTING", events.next()); assertEquals("RECONNECTING", events.next());
                assertEquals("CONNECTED", events.next());
            }
        }
    }
    @Test public void rejectsBinaryBeforeStartAndByteMismatch() throws Exception {
        for (boolean beforeStart : new boolean[]{true, false}) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override public void onOpen(WebSocket ws, Response response) {
                        if (!beforeStart) ws.send(START);
                        ws.send(ByteString.of(new byte[3200]));
                        if (!beforeStart) ws.send("{\"type\":\"end\",\"id\":\"r1\",\"bytes\":123}");
                    }
                }));
                server.start();
                try (WebSocketAudioReceiver receiver = receiver(server)) {
                    Events events = new Events(); receiver.connect("192.168.1.5", events);
                    assertEquals("CONNECTING", events.next()); assertEquals("CONNECTED", events.next());
                    if (!beforeStart) { assertEquals("START", events.next()); assertEquals("AUDIO:3200", events.next()); }
                    assertTrue(events.next().startsWith("ERROR:"));
                }
            }
        }
    }
}
