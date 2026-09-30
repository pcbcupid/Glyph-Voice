package com.pcbcupid.voice.network;

/** Manual discovery for v1. IP literals avoid DNS and any cloud lookup. */
public final class LocalEndpoint {
    private LocalEndpoint() {}
    public static String url(String address) {
        String[] parts = address.trim().split(":", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Enter a local IPv4 address, optionally followed by :port");
        String[] octets = parts[0].split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("Enter the Glyph's local IPv4 address");
        int[] ip = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (!octets[i].matches("0|[1-9][0-9]{0,2}")) throw new NumberFormatException();
                ip[i] = Integer.parseInt(octets[i]);
                if (ip[i] > 255) throw new NumberFormatException();
            }
            int port = parts.length == 2 ? Integer.parseInt(parts[1]) : 8080;
            if (port < 1 || port > 65535) throw new NumberFormatException();
            boolean local = ip[0] == 10 || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
                    || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 169 && ip[1] == 254);
            if (!local) throw new IllegalArgumentException("Use a private local Wi-Fi address (10.x, 172.16–31.x, 192.168.x or 169.254.x)");
            return "ws://" + parts[0] + ":" + port + "/audio";
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid IPv4 address or port");
        }
    }
}
