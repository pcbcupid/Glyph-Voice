package com.pcbcupid.voice.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.Test;
import static org.junit.Assert.*;

public class LatestStateDeliveryTest {
    @Test public void stalledScreenReceivesOnlyNewestSnapshotWithOnePendingTask() {
        Queue<Runnable> main = new ArrayDeque<>();
        List<String> shown = new ArrayList<>();
        LatestStateDelivery<String> delivery = new LatestStateDelivery<>(main::add, shown::add);
        for (int i = 0; i < 10_000; i++) delivery.submit("All recognized words through " + i);
        assertEquals(1, main.size());
        assertTrue(shown.isEmpty());
        main.remove().run();
        assertEquals(List.of("All recognized words through 9999"), shown);
        assertTrue(main.isEmpty());
    }

    @Test public void reattachingPullsLatestImmediatelyWithoutReplayingPendingTask() {
        Queue<Runnable> main = new ArrayDeque<>();
        List<String> shown = new ArrayList<>();
        LatestStateDelivery<String> delivery = new LatestStateDelivery<>(main::add, shown::add);
        delivery.submit("first words");
        delivery.submit("all the final words");
        delivery.deliverLatest();
        assertEquals(List.of("all the final words"), shown);
        main.remove().run();
        assertEquals(1, shown.size());
        delivery.submit("next recording");
        main.remove().run();
        assertEquals(List.of("all the final words", "next recording"), shown);
    }

    @Test public void producerDuringRenderingGetsAnotherLatestOnlyTurn() {
        Queue<Runnable> main = new ArrayDeque<>();
        List<String> shown = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<LatestStateDelivery<String>> ref = new java.util.concurrent.atomic.AtomicReference<>();
        LatestStateDelivery<String> delivery = new LatestStateDelivery<>(main::add, value -> {
            shown.add(value);
            if (value.equals("one")) {
                ref.get().submit("two");
                ref.get().submit("three");
            }
        });
        ref.set(delivery);
        delivery.submit("one");
        main.remove().run();
        assertEquals(1, main.size());
        main.remove().run();
        assertEquals(List.of("one", "three"), shown);
    }

    @Test public void newValueAfterImmediateRefreshStillUsesOnePendingTask() {
        Queue<Runnable> main = new ArrayDeque<>();
        List<Integer> shown = new ArrayList<>();
        LatestStateDelivery<Integer> delivery = new LatestStateDelivery<>(main::add, shown::add);
        delivery.submit(1);
        delivery.deliverLatest();
        delivery.submit(2);
        delivery.submit(3);
        assertEquals(1, main.size());
        main.remove().run();
        assertEquals(List.of(1, 3), shown);
    }

    @Test public void closingPreventsStaleCallbacksAndReleasesLatestSnapshot() {
        Queue<Runnable> main = new ArrayDeque<>();
        List<String> shown = new ArrayList<>();
        LatestStateDelivery<String> delivery = new LatestStateDelivery<>(main::add, shown::add);
        delivery.submit("sensitive words");
        delivery.close();
        delivery.deliverLatest();
        main.remove().run();
        delivery.submit("ignored");
        assertTrue(shown.isEmpty());
        assertTrue(main.isEmpty());
    }
}
