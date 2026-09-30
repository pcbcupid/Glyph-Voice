package com.pcbcupid.voice.network;

import com.pcbcupid.voice.audio.AudioFormat;
import com.pcbcupid.voice.audio.AudioBuffer;
import com.pcbcupid.voice.audio.AudioReceiver;
import java.util.concurrent.*;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/** Replace this adapter if the actual firmware uses a different protocol. */
public final class WebSocketAudioReceiver implements AudioReceiver {
    public interface ClientFactory {
        OkHttpClient create() throws Exception;
        default OkHttpClient create(String endpoint) throws Exception { return create(); }
    }
    private final ClientFactory factory;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Listener listener;
    private OkHttpClient client;
    private WebSocket socket;
    private String url, session;
    private int attempt;
    private long bytes, generation;
    private boolean active, closed;
    private boolean remoteStop;
    private ScheduledFuture<?> retry, inactivity;

    public WebSocketAudioReceiver(ClientFactory factory) { this.factory = factory; }
    @Override public synchronized void connect(String address, Listener listener) {
        String parsed = LocalEndpoint.url(address);
        if (closed) throw new IllegalStateException("Receiver is closed");
        disconnect();
        this.listener = listener;
        url = parsed;
        active = true;
        attempt = 0;
        open();
    }
    private synchronized void open() {
        if (!active) return;
        long token = ++generation;
        listener.onConnection(attempt == 0 ? Connection.CONNECTING : Connection.RECONNECTING);
        try {
            client = factory.create(url);
            socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    synchronized (WebSocketAudioReceiver.this) {
                        if (!current(token)) { ws.cancel(); return; }
                        attempt = 0;
                        listener.onConnection(Connection.CONNECTED);
                    }
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    synchronized (WebSocketAudioReceiver.this) {
                        if (!current(token)) return;
                        try { control(text); }
                        catch (Exception e) { fail("Audio protocol error: " + e.getMessage()); }
                    }
                }
                @Override public void onMessage(WebSocket ws, ByteString data) {
                    synchronized (WebSocketAudioReceiver.this) {
                        if (!current(token)) return;
                        try {
                            if (session == null) throw new IllegalArgumentException("PCM arrived before start");
                            if (data.size() == 0 || data.size() > AudioBuffer.MAX_PACKET_BYTES || data.size() % 2 != 0)
                                throw new IllegalArgumentException("Invalid PCM packet size or alignment");
                            bytes += data.size();
                            armInactivity();
                            listener.onAudio(data.toByteArray());
                        } catch (Exception e) { fail("Audio protocol error: " + e.getMessage()); }
                    }
                }
                @Override public void onClosing(WebSocket ws, int code, String reason) {
                    synchronized (WebSocketAudioReceiver.this) {
                        if (current(token)) fail("Connection lost. Reconnecting…");
                    }
                }
                @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                    synchronized (WebSocketAudioReceiver.this) {
                        if (current(token)) fail("Glyph unavailable or connection lost. Check power and the same Wi-Fi network. Reconnecting…");
                    }
                }
            });
        } catch (Exception e) {
            fail("Glyph unavailable. Connect to the same Wi-Fi network. Reconnecting…");
        }
    }
    private boolean current(long token) { return active && token == generation; }
    private void control(String text) throws Exception {
        if (text.length() > 2048) throw new IllegalArgumentException("Control message too large");
        JSONObject json = new JSONObject(text);
        String type = json.getString("type");
        if ("start".equals(type)) {
            if (session != null) throw new IllegalArgumentException("Duplicate start");
            if (integer(json, "version") != 1) throw new IllegalArgumentException("Expected protocol version 1");
            String id = json.getString("id");
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid recording id");
            long declaredRate = integer(json, "sampleRate");
            long channels = integer(json, "channels");
            if (declaredRate > 48000 || channels != 1) throw new IllegalArgumentException("Unsupported sample rate or channels");
            AudioFormat format = new AudioFormat((int) declaredRate, (int) channels, json.getString("encoding"));
            session = id;
            remoteStop = "stop-v1".equals(json.optString("control", ""));
            bytes = 0;
            armInactivity();
            listener.onStart(id, format);
        } else if ("end".equals(type)) {
            if (session == null || !session.equals(json.getString("id")))
                throw new IllegalArgumentException("End does not match recording id");
            if (integer(json, "bytes") != bytes) throw new IllegalArgumentException("Audio byte count mismatch");
            String id = session;
            clearSession();
            listener.onEnd(id);
        } else throw new IllegalArgumentException("Unknown control message: " + type);
    }
    private static long integer(JSONObject json, String field) throws Exception {
        Object value = json.get(field);
        if (!(value instanceof Number)) throw new IllegalArgumentException(field + " must be an integer");
        Number number = (Number) value;
        long result = number.longValue();
        if (result < 0 || number.doubleValue() != result) throw new IllegalArgumentException(field + " must be a nonnegative integer");
        return result;
    }
    private void armInactivity() {
        if (inactivity != null) inactivity.cancel(false);
        inactivity = scheduler.schedule(() -> {
            synchronized (this) { if (session != null) fail("No audio received for 5 seconds. Recording interrupted; words kept."); }
        }, 5, TimeUnit.SECONDS);
    }
    private void clearSession() {
        session = null;
        remoteStop = false;
        bytes = 0;
        if (inactivity != null) inactivity.cancel(false);
    }
    @Override public synchronized StopResult requestStop() {
        if (!active || socket == null || session == null) return StopResult.NOT_RECORDING;
        if (!remoteStop) return StopResult.UNSUPPORTED;
        // IDs were validated on start. Compact, unambiguous firmware control frame.
        return socket.send("STOP " + session) ? StopResult.SENT : StopResult.FAILED;
    }
    private void releaseSocket() {
        ++generation;
        clearSession();
        if (socket != null) { socket.cancel(); socket = null; }
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
            client = null;
        }
    }
    private void fail(String message) {
        releaseSocket();
        if (!active) return;
        listener.onError(message);
        listener.onConnection(Connection.RECONNECTING);
        long delay = Math.min(15, 1L << Math.min(attempt++, 4));
        retry = scheduler.schedule(this::open, delay, TimeUnit.SECONDS);
    }
    @Override public synchronized void disconnect() {
        active = false;
        if (retry != null) retry.cancel(false);
        releaseSocket();
        if (listener != null) listener.onConnection(Connection.DISCONNECTED);
    }
    @Override public synchronized void close() {
        disconnect();
        closed = true;
        scheduler.shutdownNow();
        listener = null;
    }
}
