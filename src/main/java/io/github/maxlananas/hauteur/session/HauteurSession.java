package io.github.maxlananas.hauteur.session;

import io.github.maxlananas.hauteur.translate.WindowedChunks;
import io.github.maxlananas.hauteur.window.WindowRules;
import org.cloudburstmc.math.vector.Vector3i;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * All mutable Hauteur state for one Bedrock player. Nothing here is global: every field belongs to a
 * single GeyserSession and is discarded with it.
 *
 * <p>Threading. The window switch runs as one task under the handler's freeze lock, on the channel event
 * loop. Fields written there are {@code volatile} so that the outbound path, which can run on Geyser's
 * tick thread, can read them. Fields that only the event loop touches are plain. {@link ChunkCache} is
 * synchronized because session and reload threads clear it. {@code ghostRevertPositions} is concurrent
 * because outbound writes add to it while the event loop drains it.</p>
 */
public final class HauteurSession {

    /** Immutable snapshot of the dimension bounds the window math needs. Recomputed at login/respawn. */
    public record WindowConfig(boolean needed, int javaMinY, int javaMaxY, int clientMinY, int clientHeight, int maxOffset) {
        public static WindowConfig of(int javaMinY, int javaMaxY, int clientMinY, int clientHeight, int maxOffsetOverride) {
            int autoMax = WindowRules.maxUsefulOffset(javaMaxY, clientMinY, clientHeight);
            boolean needed = WindowRules.windowingNeeded(javaMinY, javaMaxY, clientMinY, clientHeight);
            int maxOffset = 0;
            if (needed) {
                maxOffset = Math.max(0, autoMax);
                if (maxOffsetOverride > 0) {
                    maxOffset = Math.min(maxOffsetOverride, maxOffset);
                }
                if (maxOffset <= 0) {
                    needed = false;
                }
            }
            return new WindowConfig(needed, javaMinY, javaMaxY, clientMinY, clientHeight, maxOffset);
        }

        public WindowedChunks.Params chunkParams(int offset) {
            return WindowedChunks.Params.of(offset, javaMinY, javaMaxY - javaMinY);
        }

        /** Whether a client-space Y is inside the window the Bedrock client can display. */
        public boolean clientYVisible(int clientY) {
            return clientY >= clientMinY && clientY < clientMinY + clientHeight;
        }
    }

    /**
     * Callback so the state can recompute its snapshot from live session data without owning the session.
     * Returns null when the dimension cannot be read right now; the caller then keeps its previous snapshot.
     */
    public interface WindowConfigSource {
        WindowConfig recompute();
    }

    private static final int WATCH_RING_LIMIT = 192;
    /** Dropped-destroy positions remembered for an authoritative re-send; beyond this, the ghost is accepted. */
    public static final int GHOST_REVERT_LIMIT = 8;

    /*
     * Slots of statsBaseline, one per counter that /hauteur stats reset. A reset copies the current sum of each
     * counter here; the displays show (sum - baseline). Slot BASELINE_RESET_MARK records that a reset happened.
     */
    public static final int BASELINE_IN_TRANSLATED = 0;
    public static final int BASELINE_OUT_TRANSLATED = 1;
    public static final int BASELINE_CHUNKS_WINDOWED = 2;
    public static final int BASELINE_CHUNK_REPLAYS = 3;
    public static final int BASELINE_WINDOW_SWITCHES = 4;
    public static final int BASELINE_DROPPED_WHILE_FROZEN = 5;
    public static final int BASELINE_CHUNK_ANOMALIES = 6;
    public static final int BASELINE_ACTIONS_HELD = 7;
    public static final int BASELINE_HELD_OVERFLOW = 8;
    public static final int BASELINE_FREEZE_COUNT = 9;
    public static final int BASELINE_FREEZE_NANOS = 10;
    public static final int BASELINE_CHUNKS_UNCACHEABLE = 11;
    public static final int BASELINE_JOURNAL_OVERFLOWS = 12;
    public static final int BASELINE_TRANSFORM_FAILURES = 13;
    public static final int BASELINE_RESET_MARK = 15;
    private static final int BASELINE_SLOTS = 16;

    private final WindowConfigSource windowConfigSource;
    /**
     * Packet classes whose transform failed during this session, mapped to the first error. Quarantine is per
     * session: a new connection starts with none, and /hauteur doctor reports this player's list only.
     */
    private final Map<String, String> quarantine = new ConcurrentHashMap<>();

    /** The downstream channel once attached; used to detach cleanly on disable/reload. */
    private volatile io.netty.channel.Channel channel;
    private volatile WindowConfig window;
    /**
     * True when the cached window snapshot may predate Geyser's own dimension setup: {@code ChunkUtils.loadDimension}
     * runs in the login/respawn translators, i.e. after this handler sees those packets. The window is therefore
     * re-derived at the next chunk or position packet, never trusted from the login packet itself.
     */
    private volatile boolean windowDirty = true;
    /** Current height offset O: client Y = real Y - O. Written only by the window switch. */
    private volatile int offset;
    private volatile boolean attached;
    /** True from the flip of a window switch until its freeze ends. Outbound writes are held or dropped meanwhile. */
    private volatile boolean frozen;
    private volatile long lastSwitchAtMs;
    private volatile boolean watch;
    /** Incremented on every freeze start and reset; a scheduled unfreeze acts only if its epoch is still current. */
    private volatile long freezeEpoch;
    /** Offset the client's frame was built with when the current freeze started. */
    private volatile int frozenFrameOffset;
    /**
     * Anchor for frame resolution: the player's last known real Y. It is set by the switch trigger (the
     * authoritative server position for teleports, the client's own position otherwise) and advanced by each
     * held packet that resolves.
     */
    private volatile double lastSeenRealY;
    /** Nanotime at which the current freeze started; bounds how far the player can plausibly have travelled. */
    private volatile long switchStartNanos;

    /** Original chunks plus journaled block changes, for replay during a window switch. Synchronized internally. */
    public final ChunkCache chunkCache;
    public final Set<Vector3i> ghostRevertPositions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Deque<String> watchRing = new ArrayDeque<>(WATCH_RING_LIMIT);

    private volatile int playerJavaId = -1;
    public final AtomicBoolean switchQueued = new AtomicBoolean();
    /** True after attach exhausted its retries: the session runs untranslated; surfaced in info/doctor. */
    private volatile boolean degraded;
    /** Incremented on login/respawn; a switch or ghost queued before a world change must not act on the new world. */
    private volatile long worldGeneration;
    /** Cooldown multiplier (1..8) while switches keep arriving back-to-back (anti oscillation storm). */
    private volatile int switchBackoff = 1;
    /** Last completed freeze duration, and running totals for the stats view. */
    private volatile long lastFreezeNanos;
    public final LongAdder freezeTotalNanos = new LongAdder();
    public final LongAdder freezeCount = new LongAdder();
    /** Baselines captured by /hauteur stats reset; see the BASELINE_ constants. */
    public final long[] statsBaseline = new long[BASELINE_SLOTS];
    public final LongAdder inTranslated = new LongAdder();
    public final LongAdder outTranslated = new LongAdder();
    public final LongAdder chunkWindowed = new LongAdder();
    public final LongAdder chunkReplays = new LongAdder();
    public final LongAdder windowSwitches = new LongAdder();
    public final LongAdder droppedWhileFrozen = new LongAdder();
    public final LongAdder chunkAnomalies = new LongAdder();
    public final LongAdder actionsHeld = new LongAdder();
    public final LongAdder heldOverflow = new LongAdder();
    public final LongAdder chunkUncacheable = new LongAdder();
    public final LongAdder journalOverflows = new LongAdder();
    public final LongAdder transformFailures = new LongAdder();

    // Accessors. Writers are the handler (transitions, on the channel's event loop, under freezeLock), HauteurCore
    // (attach and detach, and the reload path) and the watch command. The fields are volatile, so readers see the
    // latest value, but a setter is not a lock: multi-field transitions still need freezeLock.

    public io.netty.channel.Channel channel() {
        return channel;
    }

    public void setChannel(io.netty.channel.Channel value) {
        this.channel = value;
    }

    public WindowConfig window() {
        return window;
    }

    public boolean windowDirty() {
        return windowDirty;
    }

    public void setWindowDirty(boolean value) {
        this.windowDirty = value;
    }

    public int offset() {
        return offset;
    }

    public void setOffset(int value) {
        this.offset = value;
    }

    public boolean attached() {
        return attached;
    }

    public void setAttached(boolean value) {
        this.attached = value;
    }

    public boolean frozen() {
        return frozen;
    }

    public void setFrozen(boolean value) {
        this.frozen = value;
    }

    public long lastSwitchAtMs() {
        return lastSwitchAtMs;
    }

    public void setLastSwitchAtMs(long value) {
        this.lastSwitchAtMs = value;
    }

    public boolean watch() {
        return watch;
    }

    public void setWatch(boolean value) {
        this.watch = value;
    }

    public long freezeEpoch() {
        return freezeEpoch;
    }

    public int frozenFrameOffset() {
        return frozenFrameOffset;
    }

    public void setFrozenFrameOffset(int value) {
        this.frozenFrameOffset = value;
    }

    public double lastSeenRealY() {
        return lastSeenRealY;
    }

    public void setLastSeenRealY(double value) {
        this.lastSeenRealY = value;
    }

    public long switchStartNanos() {
        return switchStartNanos;
    }

    public void setSwitchStartNanos(long value) {
        this.switchStartNanos = value;
    }

    public int playerJavaId() {
        return playerJavaId;
    }

    public void setPlayerJavaId(int value) {
        this.playerJavaId = value;
    }

    public boolean degraded() {
        return degraded;
    }

    public void setDegraded(boolean value) {
        this.degraded = value;
    }

    public long worldGeneration() {
        return worldGeneration;
    }

    public int switchBackoff() {
        return switchBackoff;
    }

    public void setSwitchBackoff(int value) {
        this.switchBackoff = value;
    }

    public long lastFreezeNanos() {
        return lastFreezeNanos;
    }

    public void setLastFreezeNanos(long value) {
        this.lastFreezeNanos = value;
    }

    /** Next freeze epoch. Called under freezeLock only. */
    public long nextFreezeEpoch() {
        return ++freezeEpoch;
    }

    /** Next world generation. Synchronised: it is bumped from login/respawn and read by queued work. */
    public synchronized long nextWorldGeneration() {
        return ++worldGeneration;
    }

    public HauteurSession(WindowConfigSource windowConfigSource, int maxChunks, long maxBytes) {
        this.windowConfigSource = windowConfigSource;
        this.chunkCache = new ChunkCache(maxChunks, maxBytes);
    }

    /**
     * Re-derives the window from live session data. When the source cannot answer (it returns null), the
     * previous snapshot is kept: a transient read failure must not switch windowing off while the client is
     * shifted, because the packets would then pass untranslated in the wrong frame.
     */
    public void refreshWindow() {
        WindowConfig next = windowConfigSource.recompute();
        if (next != null) {
            this.window = next;
        }
    }

    public void resetChunkCache() {
        chunkCache.clear();
    }

    /** Records a packet type that failed to transform. The first error for a type is kept. */
    public void recordQuarantine(String typeName, String reason) {
        quarantine.putIfAbsent(typeName, reason);
    }

    /** Packet types quarantined in this session, with their first error. A live, read-only view. */
    public Map<String, String> quarantine() {
        return Collections.unmodifiableMap(quarantine);
    }

    public synchronized void pushWatchLine(String line) {
        if (watchRing.size() >= WATCH_RING_LIMIT) {
            watchRing.pollFirst();
        }
        watchRing.addLast(line);
    }

    public synchronized List<String> watchSnapshot() {
        return new ArrayList<>(watchRing);
    }
}
