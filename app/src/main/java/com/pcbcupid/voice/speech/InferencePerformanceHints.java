package com.pcbcupid.voice.speech;

import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.Process;

/** Advisory CPU scheduling, never a prerequisite for recognition. Worker-owned. */
final class InferencePerformanceHints implements AutoCloseable {
    private PerformanceHintManager.Session session;
    private long frames, workNanos;
    InferencePerformanceHints(Context context) {
        if (context != null && Build.VERSION.SDK_INT >= 31) {
            try {
                PerformanceHintManager manager = context.getSystemService(PerformanceHintManager.class);
                if (manager != null) session = manager.createHintSession(new int[]{Process.myTid()}, 1_000_000_000L);
            } catch (RuntimeException ignored) { /* Optional OEM feature. */ }
        }
    }
    boolean active() { return session != null; }
    void report(int inputFrames, int rate, long work, boolean decoded) {
        if (Build.VERSION.SDK_INT < 31 || session == null) return;
        frames += inputFrames;
        workNanos += work;
        if (!decoded) return;
        try {
            // Budget is incoming audio duration, not time since the screen opened.
            // Leave a little headroom for networking/features without asking for an
            // unbounded CPU boost. Do not include waits for new audio in work time.
            session.updateTargetWorkDuration(Math.max(1, frames * 1_000_000_000L / rate * 9 / 10));
            session.reportActualWorkDuration(Math.max(1, workNanos));
        } catch (RuntimeException ignored) { close(); }
        frames = workNanos = 0;
    }
    @Override public void close() {
        if (Build.VERSION.SDK_INT >= 31 && session != null) {
            try { session.close(); } catch (RuntimeException ignored) { }
            session = null;
        }
    }
}
