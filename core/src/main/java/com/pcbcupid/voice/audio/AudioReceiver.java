package com.pcbcupid.voice.audio;

/** Callbacks are ordered, but may arrive on a background thread. */
public interface AudioReceiver extends AutoCloseable {
    enum Connection { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }
    enum StopResult { SENT, UNSUPPORTED, NOT_RECORDING, FAILED }
    interface Listener {
        void onConnection(Connection state);
        void onStart(String id, AudioFormat format);
        void onAudio(byte[] pcm);
        void onEnd(String id);
        void onError(String message);
        /** Missing packets with a still-open socket are a warning, not an end frame. */
        default void onWaitingForAudio(String message) {}
    }
    void connect(String address, Listener listener);
    void disconnect();
    /** Request capture stop; only the device's matching end frame completes audio. */
    default StopResult requestStop() { return StopResult.UNSUPPORTED; }
    @Override void close();
}
