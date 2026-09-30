package com.pcbcupid.voice.network;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class BleProvisioningProtocolTest {
    @Test public void matchesFirmwareGoldenFrame() {
        List<byte[]> frames = BleProvisioningProtocol.credentials("Lab", "testpass");
        assertEquals(3, frames.size());
        assertArrayEquals(new byte[]{1, 13}, frames.get(0));
        assertArrayEquals(new byte[]{2, 0, 3, 8, 76, 97, 98, 116, 101, 115, 116, 112, 97, 115, 115}, frames.get(1));
        assertArrayEquals(new byte[]{3}, frames.get(2));
    }
    @Test public void maxCredentialsFitDefaultMtuAndPreserveWhitespace() {
        List<byte[]> frames = BleProvisioningProtocol.credentials("x".repeat(32), "y".repeat(63));
        assertEquals(8, frames.size()); assertEquals(97, frames.get(0)[1]);
        int offset = 0;
        for (int i = 1; i < frames.size() - 1; i++) { byte[] f = frames.get(i); assertTrue(f.length <= 20); assertEquals(offset, f[1]); offset += f.length - 2; }
        assertEquals(97, offset);
        assertEquals(32, BleProvisioningProtocol.credentials("é".repeat(16), "testpass").get(1)[2]);
        assertEquals(5, BleProvisioningProtocol.credentials(" Lab ", "testpass").get(1)[2]);
    }
    @Test public void rejectsBadCredentials() {
        for (String ssid : new String[]{"", "x".repeat(33), "é".repeat(17), "x\0y"})
            assertThrows(IllegalArgumentException.class, () -> BleProvisioningProtocol.credentials(ssid, "testpass"));
        for (String key : new String[]{"short", "x".repeat(64), "test\npass", "passwörd"})
            assertThrows(IllegalArgumentException.class, () -> BleProvisioningProtocol.credentials("Lab", key));
    }
    private String status = "{\"version\":1,\"id\":\"aabbccddeeff\",\"state\":\"connected\",\"ip\":\"192.168.4.2\",\"port\":8080,\"path\":\"/audio\"}";
    @Test public void validatesDiscoveredEndpoint() throws Exception {
        BleProvisioningProtocol.Status parsed = BleProvisioningProtocol.status(status.getBytes(StandardCharsets.UTF_8));
        assertEquals("AABBCCDDEEFF", parsed.id); assertEquals("192.168.4.2:8080", parsed.address);
        for (String bad : new String[]{"{}", " ".repeat(225), status.replace("192.168.4.2", "8.8.8.8"), status.replace("/audio", "/other"), status.replace("8080", "80"), status.replace("connected", "unknown"), status.replace("aabbccddeeff", "bad"), status.replace("\"version\":1", "\"version\":2")})
            assertThrows(Exception.class, () -> BleProvisioningProtocol.status(bad.getBytes(StandardCharsets.UTF_8)));
    }
}
