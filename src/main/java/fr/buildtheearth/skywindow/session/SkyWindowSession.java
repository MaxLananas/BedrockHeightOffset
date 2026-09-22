package fr.buildtheearth.skywindow.session;

import fr.buildtheearth.skywindow.translate.WindowedChunks;
import fr.buildtheearth.skywindow.window.WindowRules;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * All mutable SkyWindow state for one Bedrock player. There deliberately is no global offset state: every
 * field belongs to a single GeyserSession and is discarded with it.
 *
 * <p>Thread model: {@code frame} and {@code window} are written on the channel event
 * loop and read from the Geyser tick loop (outbound writes), hence volatile. The chunk cache and
 * teleport snapshot are event-loop-confined. The whole window switch executes as one
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
        /** Lifetime count of LRU evictions (the evicted chunk may still be loaded at the client). */
        public int evictions;

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
                evictions++;
            }
        }

        private static Long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }
    }

    private final WindowConfigSource windowConfigSource;

    /** The downstream channel once attached; used to detach cleanly on disable/reload. */
    public volatile io.netty.channel.Channel channel;
    public volatile WindowConfig window;
    /** Server command tree index (rebuilt whenever the server re-sends its Brigadier tree). */
    public volatile fr.buildtheearth.skywindow.translate.CommandTreeIndex commandTree;
    /** View->wire text mappings of in-flight tab-completion requests (see SuggestionRanges). */
    public final fr.buildtheearth.skywindow.translate.SuggestionRanges.Journal suggestionJournal =
        new fr.buildtheearth.skywindow.translate.SuggestionRanges.Journal();
    /**
     * True when the cached window snapshot may predate Geyser's own dimension setup:
     * {@code ChunkUtils.loadDimension} runs in the login/respawn TRANSLATORS, i.e. strictly after
     * this handler sees those packets. The window is therefore re-derived at the first chunk packet
     * (which cannot arrive before the dimension is set), never on the stale snapshot itself.
     */
    public volatile boolean windowDirty = true;
    public volatile boolean attached;
    public volatile long lastSwitchAtMs;
    /**
     * The frame triple every packet must read coherently: wire offset, freeze flag, and the offset the
     * frozen client frame was built with. One immutable snapshot behind one volatile reference - two
     * separate volatiles tear under inter-thread stalls (docs/EXTREME-AUDIT.md CR1).
     */
    public record FrameState(int offset, boolean frozen, int frozenFrameOffset) {
        public static final FrameState INITIAL = new FrameState(0, false, 0);
    }

    public volatile FrameState frame = FrameState.INITIAL;

    // Event-loop confined:
    public final ChunkCache chunkCache;
    /** One-shot warning latch for replay-cache eviction of chunks the client still holds (audit CR3). */
    public boolean cacheEvictionWarned;
    public ClientboundTeleportEntityPacket lastPlayerTeleport;
    public int playerJavaId = -1;
    /**
     * Dropped dig/place targets awaiting an authoritative block re-send on unfreeze, stored in REAL
     * space (resolved from the sender's frame when dropped) so the re-send projects correctly into
     * whatever frame the client holds at unfreeze time. Written from the writer thread (Geyser tick
     * loop) and drained on the channel event loop, hence concurrent.
     */
    public final java.util.Set<Vector3i> ghostRevertPositions =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    public final AtomicBoolean switchQueued = new AtomicBoolean();
    /** Incremented on login/respawn; stale freeze artifacts (ghosts, held writes) check against it. */
    public volatile long worldGeneration;
    /** The player's physical (real-space) Y from the last movement packet; frame-resolution basis. */
    public volatile double lastSeenRealY;
    /** Cooldown multiplier (1..8) while switches keep arriving back-to-back (anti oscillation storm). */
    public volatile int switchBackoff = 1;
    /** Cap on remembered ghost-revert targets (cosmetic best-effort, see EXTREME-AUDIT). */
    public static final int GHOST_REVERT_LIMIT = 8;

    public SkyWindowSession(WindowConfigSource windowConfigSource, int maxChunks, long maxBytes) {
        this.windowConfigSource = windowConfigSource;
        this.chunkCache = new ChunkCache(maxChunks, maxBytes);
    }

    /** Clears the freeze bit in place; safe from any unwind path, any number of times. */
    public void unfreezeFrame() {
        FrameState f = frame;
        frame = new FrameState(f.offset(), false, f.offset());
    }

    public void refreshWindow() {
        this.window = windowConfigSource.recompute();
    }

    public void resetChunkCache() {
        chunkCache.clear();
    }
}
