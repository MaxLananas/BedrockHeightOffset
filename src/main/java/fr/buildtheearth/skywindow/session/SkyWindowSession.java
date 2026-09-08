package fr.buildtheearth.skywindow.session;

import fr.buildtheearth.skywindow.translate.WindowedChunks;
import fr.buildtheearth.skywindow.window.WindowRules;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * All mutable SkyWindow state for one Bedrock player. There deliberately is no global offset state: every
 * field belongs to a single GeyserSession and is discarded with it.
 *
 * <p>Thread model: {@code offset}, {@code frozen} and {@code window} are written on the channel event
 * loop and read from the Geyser tick loop (outbound writes), hence volatile. The chunk cache, watch
 * ring and teleport snapshot are event-loop-confined. The whole window switch executes as one
 * event-loop task, which is what makes the offset flip atomic with respect to packet translation.</p>
 */
public final class SkyWindowSession {

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
    }

    /** Callback so the state can recompute its snapshot from live session data without owning the session. */
    public interface WindowConfigSource {
        WindowConfig recompute();
    }

    /**
     * Original (real-space) chunk packets kept for replay during a window switch. Bounded by both
     * count and total payload bytes so large view distances cannot balloon memory. Access-order LRU;
     * only ever touched on the owning session's event loop.
     */
    public static final class ChunkCache {
        private final LinkedHashMap<Long, ClientboundLevelChunkWithLightPacket> byKey;
        private final int maxChunks;
        private final long maxBytes;
        private long bytes;

        public ChunkCache(int maxChunks, long maxBytes) {
            this.maxChunks = maxChunks;
            this.maxBytes = maxBytes;
            this.byKey = new LinkedHashMap<>(Math.min(256, maxChunks * 2), 0.75f, true);
        }

        private static long estimatedSize(ClientboundLevelChunkWithLightPacket p) {
            return 64 + p.getChunkData().length + 48L * p.getBlockEntities().length;
        }

        public void put(ClientboundLevelChunkWithLightPacket packet) {
            Long key = key(packet.getX(), packet.getZ());
            ClientboundLevelChunkWithLightPacket old = byKey.put(key, packet);
            bytes -= old == null ? 0 : estimatedSize(old);
            bytes += estimatedSize(packet);
            evictIfNeeded();
        }

        public void forget(int chunkX, int chunkZ) {
            ClientboundLevelChunkWithLightPacket removed = byKey.remove(key(chunkX, chunkZ));
            if (removed != null) {
                bytes -= estimatedSize(removed);
            }
        }

        public void clear() {
            byKey.clear();
            bytes = 0;
        }

        /** All cached chunks ordered nearest-first around the given block position. */
        public List<ClientboundLevelChunkWithLightPacket> nearestFirst(int blockX, int blockZ) {
            List<ClientboundLevelChunkWithLightPacket> list = new ArrayList<>(byKey.values());
            int pcx = blockX >> 4;
            int pcz = blockZ >> 4;
            list.sort(Comparator.comparingLong(c -> {
                long dx = c.getX() - pcx;
                long dz = c.getZ() - pcz;
                return dx * dx + dz * dz;
            }));
            return list;
        }

        public int size() {
            return byKey.size();
        }

        private void evictIfNeeded() {
            while (byKey.size() > maxChunks || (bytes > maxBytes && byKey.size() > 32)) {
                Map.Entry<Long, ClientboundLevelChunkWithLightPacket> eldest =
                    byKey.entrySet().iterator().next();
                byKey.remove(eldest.getKey());
                bytes -= estimatedSize(eldest.getValue());
            }
        }

        private static Long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }
    }

    private static final int WATCH_RING_LIMIT = 192;

    private final WindowConfigSource windowConfigSource;

    /** The downstream channel once attached; used to detach cleanly on disable/reload. */
    public volatile io.netty.channel.Channel channel;
    public volatile WindowConfig window;
    /**
     * True when the cached window snapshot may predate Geyser's own dimension setup:
     * {@code ChunkUtils.loadDimension} runs in the login/respawn TRANSLATORS, i.e. strictly after
     * this handler sees those packets. The window is therefore re-derived at the first chunk packet
     * (which cannot arrive before the dimension is set), never on the stale snapshot itself.
     */
    public volatile boolean windowDirty = true;
    public volatile int offset;
    public volatile boolean attached;
    public volatile boolean frozen;
    public volatile long lastSwitchAtMs;
    public volatile boolean watch;

    // Event-loop confined:
    public final ChunkCache chunkCache;
    public ClientboundTeleportEntityPacket lastPlayerTeleport;
    public int playerJavaId = -1;
        /**
     * Dropped-destroy positions awaiting an authoritative re-send. Written from the writer thread
     * (Geyser tick loop) and drained on the channel event loop, hence concurrent.
     */
    public final java.util.Set<Vector3i> ghostRevertPositions =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final Deque<String> watchRing = new ArrayDeque<>(WATCH_RING_LIMIT);

    public final AtomicBoolean switchQueued = new AtomicBoolean();
    public final LongAdder inTranslated = new LongAdder();
    public final LongAdder outTranslated = new LongAdder();
    public final LongAdder chunkWindowed = new LongAdder();
    public final LongAdder chunkReplays = new LongAdder();
    public final LongAdder windowSwitches = new LongAdder();
    public final LongAdder droppedWhileFrozen = new LongAdder();
    public final LongAdder chunkAnomalies = new LongAdder();
    public final LongAdder actionsHeld = new LongAdder();

    public SkyWindowSession(WindowConfigSource windowConfigSource, int maxChunks, long maxBytes) {
        this.windowConfigSource = windowConfigSource;
        this.chunkCache = new ChunkCache(maxChunks, maxBytes);
    }

    public void refreshWindow() {
        this.window = windowConfigSource.recompute();
    }

    public void resetChunkCache() {
        chunkCache.clear();
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
