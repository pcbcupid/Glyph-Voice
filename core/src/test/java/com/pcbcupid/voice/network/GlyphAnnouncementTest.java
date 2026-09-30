package com.pcbcupid.voice.network;

import java.net.InetAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class GlyphAnnouncementTest {
    private final String valid = "{\"service\":\"glyph-voice\",\"version\":1,\"id\":\"AABBCCDDEEFF\",\"port\":8080,\"path\":\"/audio\"}";
    @Test public void usesThePrivatePacketSenderAndStableId() throws Exception {
        GlyphAnnouncement board = GlyphAnnouncement.parse(valid.substring(0, valid.length()-1) + ",\"host\":\"8.8.8.8\"}", InetAddress.getByName("192.168.43.22"));
        assertEquals("192.168.43.22:8080", board.address);
        assertEquals("GLYPH DDEEFF", board.toString());
    }
    @Test public void rejectsUnknownServicesVersionsPortsIdsAndPublicSources() throws Exception {
        for (String message : new String[]{"{}", valid.replace("glyph-voice", "other"), valid.replace("8080", "80"), valid.replace("/audio", "/admin"), valid.replace(":1,", ":2,"), valid.replace("AABBCCDDEEFF", "bad"), " ".repeat(513)}) {
            try { GlyphAnnouncement.parse(message, InetAddress.getByName("192.168.43.22")); fail(message); } catch (Exception expected) { }
        }
        try { GlyphAnnouncement.parse(valid, InetAddress.getByName("8.8.8.8")); fail("Public source"); } catch (IllegalArgumentException expected) { }
    }
}
