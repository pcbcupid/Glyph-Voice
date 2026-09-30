package com.pcbcupid.voice.network;

import java.net.InetAddress;
import org.json.JSONObject;

/** Discovery describes the sender only, never a third-party destination supplied in a packet. */
public final class GlyphAnnouncement {
    public static final int PORT = 40123;
    public final String id, address;
    private GlyphAnnouncement(String id, String address) { this.id = id; this.address = address; }
    public static GlyphAnnouncement parse(String packet, InetAddress sender) throws Exception {
        if (packet.length() > 512) throw new IllegalArgumentException("Announcement too large");
        JSONObject json = new JSONObject(packet);
        if (!"glyph-voice".equals(json.getString("service")) || json.getInt("version") != 1
                || !"/audio".equals(json.getString("path"))) throw new IllegalArgumentException("Unknown board service");
        String id = json.getString("id");
        if (!id.matches("[A-Fa-f0-9]{12}")) throw new IllegalArgumentException("Invalid board ID");
        int port = json.getInt("port");
        if (port != 8080) throw new IllegalArgumentException("Unknown board port");
        String address = sender.getHostAddress() + ":" + port;
        LocalEndpoint.url(address);
        return new GlyphAnnouncement(id.toUpperCase(java.util.Locale.ROOT), address);
    }
    @Override public String toString() { return "GLYPH " + id.substring(6); }
}
