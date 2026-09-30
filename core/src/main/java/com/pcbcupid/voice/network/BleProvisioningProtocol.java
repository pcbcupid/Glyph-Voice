package com.pcbcupid.voice.network;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Same bounded framing as firmware ProvisioningFrame.h and the browser adapter. */
public final class BleProvisioningProtocol {
    public static final UUID SERVICE = UUID.fromString("c8c0f100-7d8c-4b9e-9a26-12f467a3e001");
    public static final UUID STATUS = UUID.fromString("c8c0f101-7d8c-4b9e-9a26-12f467a3e001");
    public static final UUID COMMAND = UUID.fromString("c8c0f102-7d8c-4b9e-9a26-12f467a3e001");
    private BleProvisioningProtocol() { }
    public static List<byte[]> credentials(String ssid, String password) {
        // SSIDs/passwords are exact values: never trim meaningful spaces.
        byte[] name = ssid.getBytes(StandardCharsets.UTF_8), key = password.getBytes(StandardCharsets.UTF_8);
        try {
            if (name.length < 1 || name.length > 32 || ssid.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Wi-Fi name must be 1–32 UTF-8 bytes, without a null character.");
            if (!password.matches("[ -~]{8,63}"))
                throw new IllegalArgumentException("Use a 2.4 GHz WPA2 network with an 8–63 character ASCII password.");
            byte[] body = new byte[2 + name.length + key.length];
            body[0] = (byte) name.length; body[1] = (byte) key.length;
            System.arraycopy(name, 0, body, 2, name.length); System.arraycopy(key, 0, body, 2 + name.length, key.length);
            List<byte[]> packets = new ArrayList<>();
            packets.add(new byte[]{1, (byte) body.length});
            for (int offset = 0; offset < body.length; offset += 18) {
                int size = Math.min(18, body.length - offset);
                byte[] packet = new byte[size + 2]; packet[0] = 2; packet[1] = (byte) offset;
                System.arraycopy(body, offset, packet, 2, size); packets.add(packet);
            }
            packets.add(new byte[]{3}); Arrays.fill(body, (byte) 0); return packets;
        } finally { Arrays.fill(name, (byte) 0); Arrays.fill(key, (byte) 0); }
    }
    public static final class Status {
        public final String id, state, address, error;
        private Status(String id, String state, String address, String error) {
            this.id = id; this.state = state; this.address = address; this.error = error;
        }
    }
    public static Status status(byte[] bytes) throws Exception {
        if (bytes.length > 224) throw new IllegalArgumentException("Setup response too large.");
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        Object version = json.get("version"), port = json.get("port");
        if (!(version instanceof Number) || ((Number) version).doubleValue() != 1
                || !(port instanceof Number) || ((Number) port).doubleValue() != 8080
                || !"/audio".equals(json.getString("path"))) throw new IllegalArgumentException("Unsupported Glyph setup protocol.");
        String id = json.getString("id"), state = json.getString("state");
        if (!id.matches("[A-Fa-f0-9]{12}") || !Arrays.asList("ready", "queued", "joining", "connected", "failed").contains(state))
            throw new IllegalArgumentException("Invalid Glyph setup response.");
        String address = "";
        if ("connected".equals(state)) { address = json.getString("ip") + ":8080"; LocalEndpoint.url(address); }
        String code = json.optString("error", "");
        String error = "network".equals(code) ? "Couldn't join Wi-Fi. Check the name, password, 2.4 GHz mode and hotspot power."
                : "storage".equals(code) ? "Glyph couldn't save Wi-Fi settings. Reset the board and retry."
                : "Setup was rejected. Reconnect and try again.";
        return new Status(id.toUpperCase(Locale.ROOT), state, address, error);
    }
}
