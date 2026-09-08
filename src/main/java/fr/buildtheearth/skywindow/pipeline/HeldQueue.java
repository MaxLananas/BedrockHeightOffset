package fr.buildtheearth.skywindow.pipeline;

import io.netty.channel.ChannelPromise;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded FIFO of outbound packets held across a window switch, replayed on unfreeze in the exact
 * order the client sent them.
 *
 * <p>Thread contract: {@link #offer} is called from whichever thread the netty pipeline invokes
 * {@code write()} on (for Geyser, that can be the game tick loop), while {@link #poll}/{@link #clear}
 * run on the channel event loop. A plain {@code ArrayDeque} would race between those; this class is
 * lock-free and safe for exactly this MPMC-lite shape. The cap is advisory (racing producers can push
 * the live size a few entries past it, never unbounded): it exists to bound memory and replay latency
 * during pathological freeze overlaps, not as a hard protocol invariant.</p>
 */
final class HeldQueue {
    /** One held write: the packet, already translated for the frame the client was in, and its promise. */
    record Entry(Object packet, ChannelPromise promise, boolean blockAction) {
    }

    private final Queue<Entry> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int limit;
    private final Runnable onOverflow;

    HeldQueue(int limit, Runnable onOverflow) {
        this.limit = limit;
        this.onOverflow = onOverflow;
    }

    /** @return true when accepted; false means the caller must fall back to its drop behaviour. */
    boolean offer(Entry entry) {
        if (size.get() >= limit) {
            onOverflow.run();
            return false;
        }
        queue.add(entry);
        size.incrementAndGet();
        return true;
    }

    Entry poll() {
        Entry entry = queue.poll();
        if (entry != null) {
            size.decrementAndGet();
        }
        return entry;
    }

    /**
     * Removes everything and completes every pending promise successfully: dropped replay queues must
     * never leave write futures of Geyser hanging (the packets are gone, not failed - failing would
     * trip reconnect paths for what is a benign, rare event).
     */
    void clearAndComplete() {
        Entry entry;
        while ((entry = poll()) != null) {
            entry.promise().trySuccess();
        }
    }

    int size() {
        return size.get();
    }
}
