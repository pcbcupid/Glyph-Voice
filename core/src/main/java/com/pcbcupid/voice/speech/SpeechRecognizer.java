package com.pcbcupid.voice.speech;

/** Consumes complete 16 kHz mono PCM16 segments. Local implementations stay offline; explicit cloud implementations may use network I/O. */
public interface SpeechRecognizer extends AutoCloseable {
    TranscriptionResult recognize(byte[] pcm) throws Exception;
    @Override void close();
}
