package io.github.maxlananas.hauteur.session;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkCacheTest {

    private static ClientboundLevelChunkWithLightPacket chunk(int x, int z, int dataBytes) {
        return new ClientboundLevelChunkWithLightPacket(x, z, new byte[dataBytes], Map.of(), new BlockEntityInfo[0],
            new LightUpdateData(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of()));
    }

    private static BlockChangeEntry block(int x, int y, int z, int state) {
        return new BlockChangeEntry(Vector3i.from(x, y, z), state);
    }

    @Test
    void journalKeepsOnlyTheLatestStatePerPosition() {
        ChunkCache cache = new ChunkCache(64, 1 << 20);
        cache.put(chunk(0, 0, 100));
        assertEquals(ChunkCache.Record.RECORDED, cache.recordBlock(block(1, 64, 1, 5)));
        assertEquals(ChunkCache.Record.RECORDED, cache.recordBlock(block(1, 64, 1, 7)));

        ChunkCache.Entry entry = cache.nearestFirst(0, 0).get(0);
        assertEquals(1, entry.blockChanges().size());
        assertEquals(7, entry.blockChanges().get(0).getBlock());
    }

    @Test
    void blockChangeSupersedesBlockEntityAtTheSamePosition() {
        ChunkCache cache = new ChunkCache(64, 1 << 20);
        cache.put(chunk(0, 0, 100));
        cache.recordBlockEntity(new ClientboundBlockEntityDataPacket(Vector3i.from(2, 70, 2), BlockEntityType.CHEST, null));
        assertEquals(1, cache.nearestFirst(0, 0).get(0).blockEntityChanges().size());

        cache.recordBlock(block(2, 70, 2, 9));
        assertEquals(0, cache.nearestFirst(0, 0).get(0).blockEntityChanges().size());
        assertEquals(1, cache.nearestFirst(0, 0).get(0).blockChanges().size());
    }

    @Test
    void changesForUncachedChunksAreNotRecorded() {
        ChunkCache cache = new ChunkCache(64, 1 << 20);
        assertEquals(ChunkCache.Record.NOT_CACHED, cache.recordBlock(block(100, 64, 100, 1)));
        assertEquals(0, cache.size());
    }

    @Test
    void freshChunkPacketReplacesItsJournal() {
        ChunkCache cache = new ChunkCache(64, 1 << 20);
        cache.put(chunk(0, 0, 100));
        cache.recordBlock(block(1, 64, 1, 5));
        cache.put(chunk(0, 0, 100));
        assertTrue(cache.nearestFirst(0, 0).get(0).blockChanges().isEmpty());
    }

    @Test
    void budgetIsStrictEvenWithTheCountFarBelowTheLimit() {
        int chunkBytes = (int) estimateOneChunkBytes();
        ChunkCache cache = new ChunkCache(1000, 2L * chunkBytes + chunkBytes / 2);
        for (int i = 0; i < 10; i++) {
            cache.put(chunk(i, 0, 1000));
            assertTrue(cache.bytes() <= 2L * chunkBytes + chunkBytes / 2, "budget exceeded at chunk " + i);
        }
        assertEquals(2, cache.size());
        assertTrue(cache.contains(9, 0), "most recent chunk must survive");
        assertFalse(cache.contains(0, 0), "oldest chunk must be evicted");
    }

    @Test
    void chunkLargerThanTheBudgetIsNotCachedAndDiscardsItsStaleVersion() {
        ChunkCache cache = new ChunkCache(64, 4096);
        assertTrue(cache.put(chunk(0, 0, 100)));
        assertFalse(cache.put(chunk(0, 0, 100_000)), "a chunk over the whole budget must not be stored");
        assertFalse(cache.contains(0, 0), "the older version of the same chunk is stale and must go");
        assertEquals(0, cache.bytes());
    }

    @Test
    void lightUpdatesCountTowardTheBudget() {
        ClientboundLevelChunkWithLightPacket noLight = chunk(0, 0, 100);
        List<byte[]> updates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            updates.add(new byte[2048]);
        }
        ClientboundLevelChunkWithLightPacket withLight = new ClientboundLevelChunkWithLightPacket(
            0, 0, new byte[100], Map.of(), new BlockEntityInfo[0],
            new LightUpdateData(new BitSet(), new BitSet(), new BitSet(), new BitSet(), updates, List.of()));

        ChunkCache cache = new ChunkCache(64, 1 << 20);
        cache.put(noLight);
        long plain = cache.bytes();
        cache.clear();
        cache.put(withLight);
        assertTrue(cache.bytes() >= plain + 10L * 2048, "light arrays are retained memory and must be counted");
    }

    @Test
    void journalOverflowEvictsTheChunkInsteadOfKeepingItStale() {
        ChunkCache cache = new ChunkCache(64, 1 << 30);
        cache.put(chunk(0, 0, 100));
        ChunkCache.Record last = ChunkCache.Record.RECORDED;
        for (int i = 0; i <= ChunkCache.MAX_JOURNAL_ENTRIES; i++) {
            // distinct positions inside chunk (0, 0): x and z in [0, 15]
            last = cache.recordBlock(block(i % 16, 64 + i / 256, (i / 16) % 16, 1));
        }
        assertEquals(ChunkCache.Record.EVICTED_JOURNAL_OVERFLOW, last);
        assertFalse(cache.contains(0, 0));
    }

    @Test
    void forgetRemovesOnlyTheNamedChunk() {
        ChunkCache cache = new ChunkCache(64, 1 << 20);
        cache.put(chunk(0, 0, 100));
        cache.put(chunk(1, 0, 100));
        cache.forget(0, 0);
        assertFalse(cache.contains(0, 0));
        assertTrue(cache.contains(1, 0));
    }

    @Test
    void resizingTheBudgetEvictsImmediately() {
        ChunkCache cache = new ChunkCache(64, 1 << 20);
        for (int i = 0; i < 8; i++) {
            cache.put(chunk(i, 0, 1000));
        }
        cache.resize(3, 1 << 20);
        assertEquals(3, cache.size());
        assertTrue(cache.contains(7, 0));
    }

    @Test
    void budgetEvictionsAreCountedForBothWritePathsAndSurviveClear() {
        ChunkCache cache = new ChunkCache(2, 1 << 20);
        for (int i = 0; i < 3; i++) {
            cache.put(chunk(i, 0, 1000));
        }
        assertEquals(1, cache.budgetEvictions(), "the count limit evicts on put");
        cache.resize(1, 1 << 20);
        assertEquals(2, cache.budgetEvictions(), "a smaller budget evicts on resize");
        cache.clear();
        assertEquals(2, cache.budgetEvictions(), "clearing the cache is not an eviction");
    }

    @Test
    void nearestFirstOrdersByChunkDistance() {
        ChunkCache cache = new ChunkCache(64, 1 << 20);
        cache.put(chunk(10, 0, 10));
        cache.put(chunk(0, 0, 10));
        cache.put(chunk(3, 0, 10));
        List<ChunkCache.Entry> order = cache.nearestFirst(0, 0);
        assertEquals(0, order.get(0).packet().getX());
        assertEquals(3, order.get(1).packet().getX());
        assertEquals(10, order.get(2).packet().getX());
    }

    /** Size of a single small chunk as the cache accounts it, measured rather than hard-coded. */
    private static long estimateOneChunkBytes() {
        ChunkCache probe = new ChunkCache(4, Long.MAX_VALUE);
        probe.put(chunk(0, 0, 1000));
        return probe.bytes();
    }

    @Test
    void nearestFirstTreatsNegativeChunksAsNeighbours() {
        ChunkCache cache = new ChunkCache(64, 1 << 20);
        cache.put(chunk(0, 0, 10));
        cache.put(chunk(-1, 0, 10));
        cache.put(chunk(-2, 0, 10));
        // Block x = -1 lies in chunk -1 (floor division), so the order is -1, then 0, then -2.
        List<Integer> order = cache.nearestFirst(-1, 0).stream().map(e -> e.packet().getX()).toList();
        assertEquals(List.of(-1, 0, -2), order);
    }

    @Test
    void journalReadsAreSafeWhileBlocksArriveOnAnotherThread() throws Exception {
        // Regression guard: a window switch reads entries while the channel records new block changes.
        ChunkCache cache = new ChunkCache(64, 1L << 30);
        cache.put(chunk(0, 0, 10));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> {
                for (int i = 0; i < 20_000; i++) {
                    cache.recordBlock(block(i % 16, 64, (i / 16) % 16, i));
                }
                return null;
            });
            Future<?> reader = pool.submit(() -> {
                for (int i = 0; i < 2_000; i++) {
                    for (ChunkCache.Entry entry : cache.nearestFirst(0, 0)) {
                        entry.blockChanges();
                        entry.blockEntityChanges();
                    }
                }
                return null;
            });
            writer.get();
            reader.get();
        } finally {
            pool.shutdownNow();
        }
        // 16 x 16 distinct positions at y = 64: the journal keeps exactly one entry per position.
        assertEquals(256, cache.nearestFirst(0, 0).get(0).blockChanges().size());
    }
}

