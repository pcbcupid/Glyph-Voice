package com.pcbcupid.voice.core;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Latest-only presentation mailbox: never queue a replay of old partial text.
 * Producers may use any thread. The executor must be serial; deliverLatest() and
 * close() belong to that same consumer thread. This is NOT an audio queue or a
 * persistence layer: recordings must be checkpointed independently.
 */
public final class LatestStateDelivery<T> implements AutoCloseable {
    private final Executor executor;
    private final Consumer<T> consumer;
    private T latest;
    private long version, deliveredVersion;
    private boolean scheduled, closed;

    public LatestStateDelivery(Executor executor, Consumer<T> consumer) {
        this.executor = executor;
        this.consumer = consumer;
    }

    public void submit(T value) {
        boolean enqueue;
        synchronized (this) {
            if (closed) return;
            latest = Objects.requireNonNull(value);
            version++;
            enqueue = !scheduled;
            scheduled = true;
        }
        if (enqueue) executor.execute(() -> deliver(true));
    }

    /** Pull the newest complete snapshot before attaching a visible screen. */
    public void deliverLatest() { deliver(false); }

    private void deliver(boolean fromQueue) {
        T value;
        synchronized (this) {
            if (fromQueue) scheduled = false;
            if (closed || version == deliveredVersion) return;
            value = latest;
            deliveredVersion = version;
        }
        // Never invoke UI/service code while holding a producer-facing lock.
        consumer.accept(value);
    }

    @Override public synchronized void close() { closed = true; latest = null; }
}
