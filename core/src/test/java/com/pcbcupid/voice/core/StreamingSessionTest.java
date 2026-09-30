package com.pcbcupid.voice.core;

import com.pcbcupid.voice.audio.*;
import com.pcbcupid.voice.speech.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic coordinator tests. These do not claim model/device accuracy. */
public class StreamingSessionTest {
    private static final class Fake implements StreamingSpeechRecognizer {
        final AtomicInteger finishes = new AtomicInteger(), closes = new AtomicInteger();
        final CountDownLatch accepted = new CountDownLatch(1);
        final Semaphore consumed = new Semaphore(0);
        CountDownLatch block;
        public Stream openStream(int rate) {
            assertEquals(16000, rate);
            return new Stream() {
                public String accept(byte[] pcm) throws Exception {
                    accepted.countDown();
                    if (block != null) block.await();
                    consumed.release();
                    return "live words";
                }
                public TranscriptionResult finish() {
                    finishes.incrementAndGet();
                    return new TranscriptionResult("live words and final word", 12);
                }
                public void close() { closes.incrementAndGet(); }
            };
        }
        public TranscriptionResult recognize(byte[] pcm) { throw new AssertionError("Batch path used"); }
        public void close() { }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue("Timed out", condition.getAsBoolean());
    }
    private StreamingTranscriptionSession session(Fake model) {
        StreamingTranscriptionSession session = new StreamingTranscriptionSession(model, Executors.newSingleThreadExecutor());
        session.setReady(true);
        session.onConnection(AudioReceiver.Connection.CONNECTED);
        return session;
    }
    @Test public void partialAppearsBeforeEndAndFinalWordsAreFlushedOnce() throws Exception {
        Fake model = new Fake();
        try (StreamingTranscriptionSession session = session(model)) {
            session.onStart("r1", AudioFormat.standard());
            session.onAudio(new byte[3200]);
            await(() -> session.snapshot().text.equals("live words"));
            assertEquals(VoiceState.Phase.RECEIVING, session.snapshot().phase);
            assertEquals(0, model.finishes.get());
            session.onEnd("r1");
            await(() -> !session.isBusy());
            assertEquals("live words and final word", session.snapshot().text);
            assertEquals(VoiceState.Phase.RESULT, session.snapshot().phase);
            assertEquals(1, model.finishes.get());
            assertEquals(1, model.closes.get());
        }
    }
    @Test public void recognitionFinishesWhileScreenDeliveryIsSuspendedAndReopensWithFinalText() throws Exception {
        Fake model = new Fake();
        java.util.Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        java.util.List<VoiceState> shown = new java.util.ArrayList<>();
        try (LatestStateDelivery<VoiceState> delivery = new LatestStateDelivery<>(main::add, shown::add);
             StreamingTranscriptionSession session = session(model)) {
            session.observe(delivery::submit);
            session.onStart("background", AudioFormat.standard());
            for (int i = 0; i < 20; i++) {
                session.onAudio(new byte[3200]);
                assertTrue(model.consumed.tryAcquire(3, TimeUnit.SECONDS));
            }
            assertEquals("live words", session.snapshot().text);
            assertTrue(shown.isEmpty());
            session.onEnd("background");
            await(() -> !session.isBusy());
            assertEquals(1, model.finishes.get());
            assertEquals(1, main.size());
            delivery.deliverLatest();
            assertEquals(1, shown.size());
            assertEquals("live words and final word", shown.get(0).text);
            assertTrue(shown.get(0).complete);
            main.remove().run();
            assertEquals(1, shown.size());
        }
    }
    @Test public void threeMinutesOfSamplesRemainLiveUntilExplicitEnd() throws Exception {
        Fake model = new Fake();
        try (StreamingTranscriptionSession session = session(model)) {
            session.onStart("long", AudioFormat.standard());
            // Feed faster than wall time, but wait for each consumption to avoid
            // triggering the independent backlog safeguard.
            for (int i = 0; i < 360; i++) {
                session.onAudio(new byte[16000]);
                assertTrue(model.consumed.tryAcquire(3, TimeUnit.SECONDS));
            }
            assertEquals(180, session.snapshot().duration, 0.001);
            assertEquals(5_760_000L, session.snapshot().bytes);
            assertEquals(VoiceState.Phase.RECEIVING, session.snapshot().phase);
            assertEquals(0, model.finishes.get());
            session.onEnd("long");
            await(() -> !session.isBusy());
            assertTrue(session.snapshot().complete);
        }
    }
    @Test public void sessionCountersCrossSigned32BitBoundary() throws Exception {
        Fake model = new Fake();
        try (StreamingTranscriptionSession session = session(model)) {
            session.onStart("long", AudioFormat.standard());
            // Seed only totals; no giant recording allocation or hours-long test.
            for (String name : new String[]{"bytes", "packets"}) {
                java.lang.reflect.Field counter = StreamingTranscriptionSession.class.getDeclaredField(name);
                counter.setAccessible(true);
                counter.setLong(session, Integer.MAX_VALUE - 1L);
            }
            session.onAudio(new byte[3200]);
            assertTrue(model.consumed.tryAcquire(3, TimeUnit.SECONDS));
            session.onAudio(new byte[3200]);
            assertTrue(model.consumed.tryAcquire(3, TimeUnit.SECONDS));
            assertEquals(Integer.MAX_VALUE - 1L + 6400, session.snapshot().bytes);
            assertEquals(Integer.MAX_VALUE + 1L, session.snapshot().packets);
            session.onEnd("long");
            await(() -> !session.isBusy());
            assertTrue(session.snapshot().complete);
        }
    }
    @Test public void disconnectKeepsPartialAndOnlyNextStartClearsIt() throws Exception {
        Fake model = new Fake();
        try (StreamingTranscriptionSession session = session(model)) {
            session.onStart("r1", AudioFormat.standard());
            session.onAudio(new byte[3200]);
            await(() -> !session.snapshot().text.isEmpty());
            session.onConnection(AudioReceiver.Connection.DISCONNECTED);
            await(() -> !session.isBusy());
            assertEquals("live words", session.snapshot().text);
            assertTrue(session.snapshot().interrupted);
            assertEquals(1, session.snapshot().recordingSequence);
            assertEquals(0, model.finishes.get());
            session.onConnection(AudioReceiver.Connection.CONNECTED);
            assertEquals("live words", session.snapshot().text);
            session.pause();
            assertEquals("live words", session.snapshot().text);
            session.onStart("r2", AudioFormat.standard());
            assertEquals("", session.snapshot().text);
            assertFalse(session.snapshot().interrupted);
            assertEquals(2, session.snapshot().recordingSequence);
            session.onAudio(new byte[3200]);
            session.onEnd("r2");
            await(() -> !session.isBusy());
            assertEquals(VoiceState.Phase.RESULT, session.snapshot().phase);
            assertEquals(2, model.closes.get());
        }
    }
    @Test public void backgroundSchedulingPauseLongerThanFourSecondsDoesNotAbort() throws Exception {
        Fake model = new Fake();
        model.block = new CountDownLatch(1);
        try (StreamingTranscriptionSession session = session(model)) {
            session.onStart("r1", AudioFormat.standard());
            session.onAudio(new byte[3200]);
            assertTrue(model.accepted.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) session.onAudio(new byte[16000]);
            assertEquals(10.0, session.bufferedSeconds(), .001);
            assertEquals(VoiceState.Phase.RECEIVING, session.snapshot().phase);
            session.onEnd("r1");
            model.block.countDown();
            await(() -> !session.isBusy());
            assertTrue(session.snapshot().complete);
            assertFalse(session.snapshot().interrupted);
        } finally { model.block.countDown(); }
    }
    @Test public void overloadDrainsAcceptedAudioAndMarksResultIncomplete() throws Exception {
        Fake model = new Fake();
        model.block = new CountDownLatch(1);
        try (StreamingTranscriptionSession session = session(model)) {
            session.onStart("r1", AudioFormat.standard());
            session.onAudio(new byte[3200]);
            assertTrue(model.accepted.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 61; i++) session.onAudio(new byte[16000]);
            assertEquals(VoiceState.Phase.PROCESSING, session.snapshot().phase);
            assertEquals(30, session.bufferedSeconds(), .001);
            model.block.countDown();
            await(() -> !session.isBusy());
            assertEquals(VoiceState.Phase.RESULT, session.snapshot().phase);
            assertTrue(session.snapshot().message.contains("overload"));
            assertTrue(session.snapshot().interrupted);
            assertFalse(session.snapshot().complete);
            assertEquals(1, model.finishes.get());
            assertEquals(61, model.consumed.availablePermits());
        } finally { model.block.countDown(); }
    }
    @Test public void completeSegmentSurvivesLaterTransportDisconnect() throws Exception {
        Fake model = new Fake();
        model.block = new CountDownLatch(1);
        try (StreamingTranscriptionSession session = session(model)) {
            session.onStart("r1", AudioFormat.standard());
            session.onAudio(new byte[3200]);
            assertTrue(model.accepted.await(3, TimeUnit.SECONDS));
            session.onEnd("r1");
            session.onError("Connection lost after end");
            session.onConnection(AudioReceiver.Connection.DISCONNECTED);
            model.block.countDown();
            await(() -> !session.isBusy());
            assertEquals(VoiceState.Phase.RESULT, session.snapshot().phase);
            assertEquals(1, model.finishes.get());
        } finally { model.block.countDown(); }
    }
    @Test public void emptyAndMalformedAudioNeverFinalize() throws Exception {
        Fake model = new Fake();
        try (StreamingTranscriptionSession session = session(model)) {
            session.onStart("empty", AudioFormat.standard());
            session.onEnd("empty");
            await(() -> !session.isBusy());
            assertEquals(VoiceState.Phase.ERROR, session.snapshot().phase);
            session.onStart("bad", AudioFormat.standard());
            session.onAudio(new byte[3]);
            await(() -> !session.isBusy());
            assertEquals(0, model.finishes.get());
        }
    }
}
