package com.pcbcupid.voice.network;

import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.Proxy;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public final class WifiClientFactory implements WebSocketAudioReceiver.ClientFactory {
    private final ConnectivityManager connectivity;
    public WifiClientFactory(Context context) {
        connectivity = context.getSystemService(ConnectivityManager.class);
    }
    @Override public OkHttpClient create() throws IOException {
        return create(null);
    }
    @Override public OkHttpClient create(String endpoint) throws IOException {
        // Receiver validates a local IPv4 literal before calling this: no DNS lookup.
        InetAddress destination = endpoint == null ? null
                : InetAddress.getByName(Uri.parse(endpoint).getHost());
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS) // No total audio-session deadline.
                .pingInterval(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false);
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            // Deliberately don't require INTERNET or VALIDATED: an offline AP is enough.
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                LinkProperties link = connectivity.getLinkProperties(network);
                // In STA+hotspot mode, don't bind a downstream hotspot address to
                // the phone's unrelated upstream Wi-Fi Network.
                if (destination == null) return builder.socketFactory(network.getSocketFactory()).build();
                if (link != null) for (RouteInfo route : link.getRoutes()) {
                    if (!route.isDefaultRoute() && route.getDestination().contains(destination))
                        return builder.socketFactory(network.getSocketFactory()).build();
                }
            }
        }
        // A phone hosting tethering is not a Wi-Fi CLIENT Network. Let the OS
        // route to its downstream local subnet instead of rejecting the socket.
        // WebSocketAudioReceiver still restricts every destination to local IPv4.
        return builder.build();
    }
}
