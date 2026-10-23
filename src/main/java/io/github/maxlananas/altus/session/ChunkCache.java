package io.github.maxlananas.altus.session;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-session store of real-space chunk packets, used to rebuild the client's view after a window
 * switch.
 *
 * <p>Each entry is the original chunk packet plus a journal of the block and block-entity changes the
 * server sent for that chunk afterwards. Without the journal, a switch would replay the chunk as it was
 * when it arrived and silently revert every later edit. A journal is keyed by position, so it keeps only
 * the latest state per block; a chunk whose journal grows past {@link #MAX_JOURNAL_ENTRIES} is evicted
 * instead of being replayed stale.</p>
 *
 * <p>The byte budget covers chunk data, light updates, heightmaps and journal entries. It is strict:
 * entries are evicted until the total fits, with no minimum entry count. A single chunk that exceeds the
 * budget by itself is not cached at all, and any previously cached version of it is discarded.</p>
 *
 * <p>All methods are synchronized. The cache is written from the channel event loop and cleared from
 * session and reload threads.</p>
 */
public final class ChunkCache {

    /** Distinct changed positions journaled per chunk before the chunk is evicted instead of replayed stale. */
    public static final int MAX_JOURNAL_ENTRIES = 4096;

    /** Result of journaling a change. */
    public enum Record {
        RECORDED,
        /** The chunk is not cached (never arrived, forgotten, or uncacheable), so there is nothing to keep current. */
        NOT_CACHED,
        /** The chunk was evicted because its journal reached {@link #MAX_JOURNAL_ENTRIES}. */
        EVICTED_JOURNAL_OVERFLOW
    }

    private static final long CHUNK_OVERHEAD_BYTES = 256;
    private static final long JOURNAL_ENTRY_BYTES = 128;
    private static final long BLOCK_ENTITY_BYTES = 64;

    /** One cached chunk: the original real-space packet and the changes received since. */
    public static final class Entry {
        private final ClientboundLevelChunkWithLightPacket packet;
        private final long baseBytes;
        private final Map<Vector3i, BlockChangeEntry> blockChanges = new HashMap<>();
        private final Map<Vector3i, ClientboundBlockEntityDataPacket> blockEntityChanges = new HashMap<>();

        private Entry(ClientboundLevelChunkWithLightPacket packet) {
            this.packet = packet;
            this.baseBytes = estimateBaseBytes(packet);
        }

        public ClientboundLevelChunkWithLightPacket packet() {
            return packet;
        }

        /** Latest block state per changed position, in real coordinates. */
        public List<BlockChangeEntry> blockChanges() {
            return new ArrayList<>(blockChanges.values());
        }

        /** Latest block-entity data per position, in real coordinates. */
        public List<ClientboundBlockEntityDataPacket> blockEntityChanges() {
            return new ArrayList<>(blockEntityChanges.values());
        }

        private int journalSize() {
            return blockChanges.size() + blockEntityChanges.size();
        }

        private long bytes() {
            return baseBytes + journalSize() * JOURNAL_ENTRY_BYTES;
        }
    }

    private final LinkedHashMap<Long, Entry> byKey = new LinkedHashMap<>(256, 0.75f, true);
    private int maxChunks;
    private long maxBytes;
    private long bytes;

    public ChunkCache(int maxChunks, long maxBytes) {
        this.maxChunks = maxChunks;
        this.maxBytes = maxBytes;
    }

    /**
     * Stores an original chunk packet, replacing any previous packet and journal for the same chunk.
     *
     * @return false when the packet alone exceeds the byte budget; it is then not cached at all
     */
    public synchronized boolean put(ClientboundLevelChunkWithLightPacket packet) {
        removeKey(key(packet.getX(), packet.getZ()));
        Entry entry = new Entry(packet);
        if (entry.bytes() > maxBytes) {
            return false;
        }
        byKey.put(key(packet.getX(), packet.getZ()), entry);
        bytes += entry.bytes();
        evictToBudget();
        return byKey.containsKey(key(packet.getX(), packet.getZ()));
    }

    /** Drops a chunk the server has unloaded. */
    public synchronized void forget(int chunkX, int chunkZ) {
        removeKey(key(chunkX, chunkZ));
    }

    public synchronized void clear() {
        byKey.clear();
        bytes = 0;
    }

    /** Applies a new byte and count budget, evicting as needed. Used when the configuration is reloaded. */
    public synchronized void resize(int newMaxChunks, long newMaxBytes) {
        this.maxChunks = newMaxChunks;
        this.maxBytes = newMaxBytes;
        evictToBudget();
    }

    /** Journals a block change at a real position. */
    public synchronized Record recordBlock(BlockChangeEntry change) {
        Vector3i pos = change.getPosition();
        Long key = key(pos.getX() >> 4, pos.getZ() >> 4);
        Entry entry = byKey.get(key);
        if (entry == null) {
            return Record.NOT_CACHED;
        }
        long before = entry.bytes();
        entry.blockEntityChanges.remove(pos); // a new block state supersedes any block entity at that position
        entry.blockChanges.put(pos, change);
        return settle(key, entry, before);
    }

    /** Journals block-entity data at a real position. A null NBT (removal) is kept as an explicit update. */
    public synchronized Record recordBlockEntity(ClientboundBlockEntityDataPacket update) {
        Vector3i pos = update.getPosition();
        Long key = key(pos.getX() >> 4, pos.getZ() >> 4);
        Entry entry = byKey.get(key);
        if (entry == null) {
            return Record.NOT_CACHED;
        }
        long before = entry.bytes();
        entry.blockEntityChanges.put(pos, update);
        return settle(key, entry, before);
    }

    /** All cached entries, nearest to the given block position first. A snapshot: safe to iterate after return. */
    public synchronized List<Entry> nearestFirst(int blockX, int blockZ) {
        List<Entry> list = new ArrayList<>(byKey.values());
        int pcx = blockX >> 4;
        int pcz = blockZ >> 4;
        list.sort(Comparator.comparingLong(e -> {
            long dx = e.packet.getX() - pcx;
            long dz = e.packet.getZ() - pcz;
            return dx * dx + dz * dz;
        }));
        return list;
    }

    public synchronized int size() {
        return byKey.size();
    }

    public synchronized long bytes() {
        return bytes;
    }

    /** Whether a chunk is currently cached (not evicted, forgotten or uncacheable). */
    public synchronized boolean contains(int chunkX, int chunkZ) {
        return byKey.containsKey(key(chunkX, chunkZ));
    }

    private Record settle(Long key, Entry entry, long before) {
        bytes += entry.bytes() - before;
        if (entry.journalSize() > MAX_JOURNAL_ENTRIES) {
            removeKey(key);
            return Record.EVICTED_JOURNAL_OVERFLOW;
        }
        evictToBudget();
        return Record.RECORDED;
    }

    private void removeKey(Long key) {
        Entry removed = byKey.remove(key);
        if (removed != null) {
            bytes -= removed.bytes();
        }
    }

    private void evictToBudget() {
        while (!byKey.isEmpty() && (byKey.size() > maxChunks || bytes > maxBytes)) {
            var eldest = byKey.entrySet().iterator().next();
            bytes -= eldest.getValue().bytes();
            byKey.remove(eldest.getKey());
        }
    }

    /**
     * Chunk data, light arrays and heightmaps are the heavy parts. Light updates are included because they
     * are retained with the packet even though Geyser does not read them; ignoring them undercounts memory
     * by a large factor on tall worlds.
     */
    private static long estimateBaseBytes(ClientboundLevelChunkWithLightPacket packet) {
        long total = CHUNK_OVERHEAD_BYTES + packet.getChunkData().length;
        for (long[] heightMap : packet.getHeightMaps().values()) {
            total += 8L * heightMap.length;
        }
        total += BLOCK_ENTITY_BYTES * packet.getBlockEntities().length;
        LightUpdateData light = packet.getLightData();
        if (light != null) {
            for (byte[] array : light.getSkyUpdates()) {
                total += array.length;
            }
            for (byte[] array : light.getBlockUpdates()) {
                total += array.length;
            }
        }
        return total;
    }

    private static Long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
