package com.pcbcupid.voice.core;

import java.util.ArrayDeque;

/** Completion events are not UI snapshots: retain each clean stop exactly once. */
public final class AutoSummaryQueue {
    public enum Offer { IGNORED, QUEUED, FULL }
    private final ArrayDeque<VoiceState> pending = new ArrayDeque<>();
    private long handledSequence;
    public synchronized Offer offer(VoiceState state) {
        if (state.recordingSequence <= handledSequence || !state.complete || state.interrupted
                || state.phase != VoiceState.Phase.RESULT) return Offer.IGNORED;
        handledSequence = state.recordingSequence;
        if (state.text.trim().isEmpty()) return Offer.IGNORED;
        // Bound pending uploads, not recording duration. Raw history is independent.
        if (pending.size() >= 8) return Offer.FULL;
        pending.add(state);
        return Offer.QUEUED;
    }
    public synchronized VoiceState poll() { return pending.poll(); }
    public synchronized boolean isEmpty() { return pending.isEmpty(); }
    public synchronized void clear() { pending.clear(); }
}
