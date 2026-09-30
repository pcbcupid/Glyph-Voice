package com.pcbcupid.voice.core;

import com.pcbcupid.voice.audio.*;
import com.pcbcupid.voice.speech.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionTest {
    private static final class FakeSpeech implements SpeechRecognizer {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        boolean wait, fail;
        public TranscriptionResult recognize(byte[] data) throws Exception {
            calls.incrementAndGet(); started.countDown();
            if (wait) release.await(5, TimeUnit.SECONDS);
            if (fail) throw new IllegalStateException("Native inference error");
            return new TranscriptionResult("turn on the lights", 42);
        }
        public void close() {}
    }
    private static void recording(TranscriptionSession session) {
        session.onStart("one", AudioFormat.standard());
        session.onAudio(new byte[3200]);
        session.onEnd("one");
    }
    @Test public void completeRecordingMovesThroughReceivingProcessingAndResult() throws Exception {
        FakeSpeech speech = new FakeSpeech(); speech.wait = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TranscriptionSession session = new TranscriptionSession(speech, new PcmProcessor(), executor)) {
            session.setReady(true); session.onConnection(AudioReceiver.Connection.CONNECTED);
            session.onStart("one", AudioFormat.standard());
            assertEquals(VoiceState.Phase.RECEIVING, session.snapshot().phase);
            session.onAudio(new byte[3200]); session.onEnd("one");
            assertTrue(speech.started.await(2, TimeUnit.SECONDS));
            assertEquals(VoiceState.Phase.PROCESSING, session.snapshot().phase);
            speech.release.countDown(); executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(VoiceState.Phase.RESULT, session.snapshot().phase);
            assertEquals("turn on the lights", session.snapshot().text);
            assertEquals(42, session.snapshot().processingMillis);
        }
    }
    @Test public void disconnectDiscardsIncompleteRecording() {
        FakeSpeech speech = new FakeSpeech();
        try (TranscriptionSession session = new TranscriptionSession(speech, new PcmProcessor(), Executors.newSingleThreadExecutor())) {
            session.setReady(true); session.onStart("one", AudioFormat.standard());
            session.onAudio(new byte[3200]); session.onConnection(AudioReceiver.Connection.RECONNECTING);
            session.onEnd("one");
            assertEquals(VoiceState.Phase.ERROR, session.snapshot().phase); assertEquals(0, speech.calls.get());
        }
    }
    @Test public void unavailableModelAndEmptyAudioAreUsefulErrors() {
        FakeSpeech speech = new FakeSpeech();
        try (TranscriptionSession session = new TranscriptionSession(speech, new PcmProcessor(), Executors.newSingleThreadExecutor())) {
            recording(session);
            assertTrue(session.snapshot().message.contains("model"));
            session.setReady(true); session.onStart("one", AudioFormat.standard()); session.onEnd("one");
            assertEquals("No audio received.", session.snapshot().message);
            assertEquals(0, speech.calls.get());
        }
    }
    @Test public void processingRejectsNewRecordingWithoutGrowingQueue() throws Exception {
        FakeSpeech speech = new FakeSpeech(); speech.wait = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TranscriptionSession session = new TranscriptionSession(speech, new PcmProcessor(), executor)) {
            session.setReady(true); recording(session); assertTrue(speech.started.await(2, TimeUnit.SECONDS));
            recording(session); assertEquals(1, speech.calls.get());
            speech.release.countDown(); executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(1, speech.calls.get());
        }
    }
    @Test public void pauseCancelsStaleResultAndNextRecordingWorks() throws Exception {
        FakeSpeech speech = new FakeSpeech(); speech.wait = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TranscriptionSession session = new TranscriptionSession(speech, new PcmProcessor(), executor)) {
            session.setReady(true); recording(session); assertTrue(speech.started.await(2, TimeUnit.SECONDS));
            session.pause(); executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals("", session.snapshot().text); assertFalse(session.isBusy());
            speech.wait = false; recording(session); executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals("turn on the lights", session.snapshot().text);
        }
    }
    @Test public void failureIsReportedAndCanBeRetried() throws Exception {
        FakeSpeech speech = new FakeSpeech(); speech.fail = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TranscriptionSession session = new TranscriptionSession(speech, new PcmProcessor(), executor)) {
            session.setReady(true); recording(session); executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(VoiceState.Phase.ERROR, session.snapshot().phase);
            speech.fail = false; recording(session); executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(VoiceState.Phase.RESULT, session.snapshot().phase);
        }
    }
    @Test public void reconnectClearsStaleTransportError() {
        try (TranscriptionSession session = new TranscriptionSession(new FakeSpeech(), new PcmProcessor(), Executors.newSingleThreadExecutor())) {
            session.onError("Glyph unavailable");
            session.onConnection(AudioReceiver.Connection.CONNECTED);
            assertEquals(VoiceState.Phase.IDLE, session.snapshot().phase);
            assertEquals("Hold the Glyph button and speak.", session.snapshot().message);
        }
    }
}
