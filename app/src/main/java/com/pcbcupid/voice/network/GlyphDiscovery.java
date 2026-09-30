package com.pcbcupid.voice.network;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** A hotspot is not an Android Wi-Fi client Network: receive direct UDP announcements on all interfaces. */
public final class GlyphDiscovery implements AutoCloseable {
    private volatile DatagramSocket socket;
    private volatile boolean closed;
    public void start(Consumer<GlyphAnnouncement> found, Consumer<String> error) {
        new Thread(() -> {
            try (DatagramSocket listener = new DatagramSocket(null)) {
                synchronized (this) {
                    if (closed) return;
                    socket = listener;
                    listener.setReuseAddress(true);
                    listener.bind(new InetSocketAddress(GlyphAnnouncement.PORT));
                }
                byte[] buffer = new byte[513];
                while (!closed) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    listener.receive(packet);
                    if (packet.getLength() > 512) continue;
                    try {
                        found.accept(GlyphAnnouncement.parse(new String(packet.getData(), 0,
                                packet.getLength(), StandardCharsets.UTF_8), packet.getAddress()));
                    } catch (Exception ignored) { /* Unrelated or malformed LAN traffic. */ }
                }
            } catch (Exception e) {
                if (!closed) error.accept("Board discovery unavailable. Check local network access and reconnect.");
            }
        }, "Glyph-discovery").start();
    }
    @Override public synchronized void close() { closed = true; if (socket != null) socket.close(); }
}
