package io.github.maxlananas.hauteur.pipeline;

import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeldQueueTest {

    /** Promise bound to an embedded channel; completion state is what the assertions read. */
    private static final class TestPromise extends DefaultChannelPromise {
        TestPromise(EmbeddedChannel channel) {
            super(channel);
        }
    }

    @Test
    void offerUpToLimitThenRejectsWithOverflowCallback() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicInteger overflows = new AtomicInteger();
        HeldQueue queue = new HeldQueue(4, overflows::incrementAndGet);
        for (int i = 0; i < 4; i++) {
            assertTrue(queue.offer(new HeldQueue.Entry("p" + i, new TestPromise(channel), false)), "offer " + i);
        }
        assertEquals(4, queue.size());
        assertFalse(queue.offer(new HeldQueue.Entry("over", new TestPromise(channel), false)));
        assertEquals(1, overflows.get());
        assertEquals(4, queue.size()); // rejected entries are not inserted
        queue.clearAndComplete();
        assertEquals(0, queue.size());
    }

    @Test
    void fifoOrderIsPreserved() {
        EmbeddedChannel channel = new EmbeddedChannel();
        HeldQueue queue = new HeldQueue(10, () -> { });
        for (int i = 0; i < 10; i++) {
            queue.offer(new HeldQueue.Entry("m" + i, new TestPromise(channel), i % 2 == 0));
        }
        for (int i = 0; i < 10; i++) {
            HeldQueue.Entry e = queue.poll();
            assertNotNull(e);
            assertEquals("m" + i, e.packet());
            assertEquals(i % 2 == 0, e.blockAction());
        }
        assertNull(queue.poll());
    }

    @Test
    void clearAndCompleteResolvesEveryPendingPromise() {
        EmbeddedChannel channel = new EmbeddedChannel();
        HeldQueue queue = new HeldQueue(10, () -> { });
        ChannelPromise[] promises = new ChannelPromise[6];
        for (int i = 0; i < 6; i++) {
            promises[i] = new TestPromise(channel);
            queue.offer(new HeldQueue.Entry("x", promises[i], false));
        }
        queue.clearAndComplete();
        for (ChannelPromise p : promises) {
            assertTrue(p.isSuccess(), "promise must not be left pending after clear");
        }
        assertEquals(0, queue.size());
    }

    @Test
    void concurrentOfferAndDrainLosesNothing() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        HeldQueue queue = new HeldQueue(1_000_000, () -> { });
        int producers = 4;
        int perProducer = 25_000;
        AtomicInteger drained = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producers);
        CountDownLatch allDone = new CountDownLatch(producers + 1);
        for (int p = 0; p < producers; p++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        queue.offer(new HeldQueue.Entry("x", new TestPromise(channel), false));
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                    allDone.countDown();
                }
            }).start();
        }
        new Thread(() -> {
            try {
                start.await();
                while (producersDone.getCount() > 0 || queue.size() > 0) {
                    if (queue.poll() != null) {
                        drained.incrementAndGet();
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                allDone.countDown();
            }
        }).start();
        start.countDown();
        allDone.await();
        assertEquals(producers * perProducer, queue.size() + drained.get(),
            "every offered entry is either queued or drained; none lost");
    }

    @Test
    void concurrentProducersNeverExceedTheLimit() throws Exception {
        // Eight producers race for a limit of 100 with nothing draining. The cap must be exact, not "about 100".
        EmbeddedChannel channel = new EmbeddedChannel();
        int limit = 100;
        int producers = 8;
        int perProducer = 5_000;
        HeldQueue queue = new HeldQueue(limit, () -> { });
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);
        for (int p = 0; p < producers; p++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        if (queue.offer(new HeldQueue.Entry("x", new TestPromise(channel), false))) {
                            accepted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(limit, accepted.get(), "exactly the limit is accepted");
        assertEquals(limit, queue.size());
    }
}
