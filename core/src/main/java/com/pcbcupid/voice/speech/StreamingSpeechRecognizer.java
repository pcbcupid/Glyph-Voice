package com.pcbcupid.voice.speech;

/** One stateful stream per physical button press. All calls run on one worker. */
public interface StreamingSpeechRecognizer extends SpeechRecognizer {
    Stream openStream(int inputSampleRate) throws Exception;
    interface Stream extends AutoCloseable {
        /** Ordered PCM16 mono chunks at the rate passed to openStream. */
        String accept(byte[] pcm) throws Exception;
        TranscriptionResult finish() throws Exception;
        /** Thread-safe cancellation hook for network streams. Native cleanup stays on the worker. */
        default void cancel() { }
        @Override void close();
    }
}
