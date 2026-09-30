package com.pcbcupid.voice.core;

import com.pcbcupid.voice.audio.AudioReceiver.Connection;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoSummaryQueueTest {
    private VoiceState state(long sequence, VoiceState.Phase phase, String text, boolean complete, boolean interrupted) {
        return new VoiceState(Connection.CONNECTED, phase, text, "", 1, 32000, 16000, 1, 10,
                sequence, complete, interrupted);
    }
    private VoiceState finished(long sequence) { return state(sequence, VoiceState.Phase.RESULT, "Final words " + sequence, true, false); }
    @Test public void onlyCleanNonemptyFinalResultsTriggerOnce() {
        AutoSummaryQueue queue = new AutoSummaryQueue();
        assertEquals(AutoSummaryQueue.Offer.IGNORED, queue.offer(state(1, VoiceState.Phase.RECEIVING, "partial", false, false)));
        assertEquals(AutoSummaryQueue.Offer.IGNORED, queue.offer(state(1, VoiceState.Phase.PROCESSING, "tail pending", false, false)));
        assertEquals(AutoSummaryQueue.Offer.QUEUED, queue.offer(finished(1)));
        assertEquals(AutoSummaryQueue.Offer.IGNORED, queue.offer(finished(1)));
        assertEquals("Final words 1", queue.poll().text);
        assertNull(queue.poll());
        assertEquals(AutoSummaryQueue.Offer.IGNORED, queue.offer(state(2, VoiceState.Phase.RESULT, "", true, false)));
        assertEquals(AutoSummaryQueue.Offer.IGNORED, queue.offer(state(3, VoiceState.Phase.RESULT, "incomplete", false, true)));
        assertEquals(AutoSummaryQueue.Offer.IGNORED, queue.offer(state(4, VoiceState.Phase.ERROR, "lost connection", false, true)));
    }
    @Test public void completionsRemainOrderedEvenIfUiSnapshotsAreCoalesced() {
        AutoSummaryQueue queue = new AutoSummaryQueue();
        queue.offer(finished(1)); queue.offer(finished(2)); queue.offer(finished(3));
        assertEquals(1, queue.poll().recordingSequence);
        assertEquals(2, queue.poll().recordingSequence);
        assertEquals(3, queue.poll().recordingSequence);
        assertTrue(queue.isEmpty());
    }
    @Test public void cancelClearsPendingButCannotReplayOldCompletion() {
        AutoSummaryQueue queue = new AutoSummaryQueue();
        queue.offer(finished(1)); queue.clear();
        assertEquals(AutoSummaryQueue.Offer.IGNORED, queue.offer(finished(1)));
        assertNull(queue.poll());
        assertEquals(AutoSummaryQueue.Offer.QUEUED, queue.offer(finished(2)));
    }
    @Test public void excessPendingUploadsAreBoundedAndNotRetriedSilently() {
        AutoSummaryQueue queue = new AutoSummaryQueue();
        for (int i = 1; i <= 8; i++) assertEquals(AutoSummaryQueue.Offer.QUEUED, queue.offer(finished(i)));
        assertEquals(AutoSummaryQueue.Offer.FULL, queue.offer(finished(9)));
        queue.poll();
        assertEquals(AutoSummaryQueue.Offer.IGNORED, queue.offer(finished(9)));
        assertEquals(AutoSummaryQueue.Offer.QUEUED, queue.offer(finished(10)));
    }
}
