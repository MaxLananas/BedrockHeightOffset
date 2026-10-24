package io.github.maxlananas.hauteur.world;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.erosion.util.BlockPositionIterator;
import org.geysermc.geyser.level.WorldManager;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShiftedWorldManagerTest {

    /**
     * Stand-in for a delegate that records the Y it was asked for and returns it as the block id. Its batch read is
     * the base class one, which calls {@code getBlockAt} per position, as Geyser's manager does without Erosion (with
     * Erosion it sends a single batch request instead).
     */
    static final class RecordingWorld extends WorldManager {
        final List<Integer> readYs = new ArrayList<>();
        final List<Integer> asyncYs = new ArrayList<>();
        final List<Integer> potYs = new ArrayList<>();
        private final boolean ownWorld;

        RecordingWorld(boolean ownWorld) {
            this.ownWorld = ownWorld;
        }

        @Override
        public int getBlockAt(GeyserSession session, int x, int y, int z) {
            readYs.add(y);
            return y;
        }

        @Override
        public CompletableFuture<Integer> getBlockAtAsync(GeyserSession session, int x, int y, int z) {
            asyncYs.add(y);
            return CompletableFuture.completedFuture(y);
        }

        @Override
        public boolean hasOwnChunkCache() {
            return ownWorld;
        }

        @Override
        public GameMode getDefaultGameMode(GeyserSession session) {
            return null;
        }

        @Override
        public void getDecoratedPotData(GeyserSession session, Vector3i pos, Consumer<List<String>> apply) {
            potYs.add(pos.getY());
        }
    }

    /** Wrapper over a delegate that reads the real world (Spigot-like, or an Erosion backend). */
    private static ShiftedWorldManager realWorld(RecordingWorld world, AtomicInteger offset) {
        return new ShiftedWorldManager(world, session -> offset.get(), session -> true);
    }

    /** Wrapper over a delegate that reads the window-space session cache (standalone, no Erosion). */
    private static ShiftedWorldManager cacheWorld(RecordingWorld world, AtomicInteger offset) {
        return new ShiftedWorldManager(world, session -> offset.get(), session -> false);
    }

    @Test
    void zeroOffsetPassesCoordinatesThrough() {
        RecordingWorld world = new RecordingWorld(true);
        ShiftedWorldManager wrapper = realWorld(world, new AtomicInteger(0));

        assertEquals(70, wrapper.getBlockAt(null, 1, 70, 2));
        assertEquals(List.of(70), world.readYs);
    }

    @Test
    void readsOfARealWorldAreShiftedByTheWindowOffset() {
        RecordingWorld world = new RecordingWorld(true);
        ShiftedWorldManager wrapper = realWorld(world, new AtomicInteger(48));

        // Client Y -10 is real Y 38 under a 48 block offset (client Y = real Y - offset).
        assertEquals(38, wrapper.getBlockAt(null, 0, -10, 0));
        assertEquals(List.of(38), world.readYs);
    }

    @Test
    void readsOfTheSessionCacheAreNotShifted() {
        // Regression: the standalone cache already holds window-space chunks. Shifting its reads sampled the chunk
        // one offset too high, so collision and swimming checks read the wrong blocks.
        RecordingWorld world = new RecordingWorld(false);
        ShiftedWorldManager wrapper = cacheWorld(world, new AtomicInteger(64));

        assertEquals(10, wrapper.getBlockAt(null, 0, 10, 0));
        assertEquals(List.of(10), world.readYs);
    }

    @Test
    void vectorAndAsyncReadsUseTheSameShift() throws Exception {
        RecordingWorld world = new RecordingWorld(true);
        ShiftedWorldManager wrapper = realWorld(world, new AtomicInteger(32));

        assertEquals(37, wrapper.getBlockAt(null, Vector3i.from(0, 5, 0)));
        assertEquals(37, (int) wrapper.getBlockAtAsync(null, 0, 5, 0).get());
        assertEquals(37, (int) wrapper.getBlockAtAsync(null, Vector3i.from(0, 5, 0)).get());
        assertEquals(List.of(37), world.readYs);
        assertEquals(List.of(37, 37), world.asyncYs);
    }

    @Test
    void asyncReadsStayAsynchronousThroughTheDelegate() throws Exception {
        // Regression: the wrapper used to inherit the base async method, which blocks on the synchronous read. An
        // Erosion backend answers asynchronously, so the delegate's own async method must be the one called.
        RecordingWorld world = new RecordingWorld(true);
        ShiftedWorldManager wrapper = realWorld(world, new AtomicInteger(16));

        assertEquals(26, (int) wrapper.getBlockAtAsync(null, 0, 10, 0).get());
        assertEquals(List.of(26), world.asyncYs);
        assertTrue(world.readYs.isEmpty(), "the synchronous read must not be used for async lookups");
    }

    @Test
    void offsetIsReadOnEveryCall() {
        RecordingWorld world = new RecordingWorld(true);
        AtomicInteger offset = new AtomicInteger(0);
        ShiftedWorldManager wrapper = realWorld(world, offset);

        assertEquals(10, wrapper.getBlockAt(null, 0, 10, 0));
        offset.set(64); // a window switch happened between two reads
        assertEquals(74, wrapper.getBlockAt(null, 0, 10, 0));
        assertEquals(List.of(10, 74), world.readYs);
    }

    @Test
    void decoratedPotLookupIsShifted() {
        RecordingWorld world = new RecordingWorld(true);
        ShiftedWorldManager wrapper = realWorld(world, new AtomicInteger(16));

        wrapper.getDecoratedPotData(null, Vector3i.from(1, 4, 1), strings -> { });
        assertEquals(List.of(20), world.potYs);
    }

    @Test
    void coordinateFreeCallsReachTheDelegateUnchanged() {
        RecordingWorld world = new RecordingWorld(true);
        ShiftedWorldManager wrapper = realWorld(world, new AtomicInteger(128));

        assertSame(world, wrapper.delegate());
        assertTrue(wrapper.hasOwnChunkCache());
    }

    @Test
    void batchReadsAreShiftedPerPosition() {
        // Two positions in one column: y = 0 and y = 1 (zxy iteration order).
        RecordingWorld world = new RecordingWorld(true);
        ShiftedWorldManager wrapper = realWorld(world, new AtomicInteger(16));

        int[] blocks = wrapper.getBlocksAt(null, BlockPositionIterator.fromMinMax(0, 0, 0, 0, 1, 0));
        assertArrayEquals(new int[] {16, 17}, blocks);
        assertEquals(List.of(16, 17), world.readYs);
    }

    @Test
    void batchReadsKeepTheBoxShapeAndIndexing() {
        // A 2 x 2 x 2 box shifted by 32: the result is indexed as the original iterator, so y = 32 for the first
        // four positions (the lower layer) and y = 33 for the next four.
        RecordingWorld world = new RecordingWorld(true);
        ShiftedWorldManager wrapper = realWorld(world, new AtomicInteger(32));

        int[] blocks = wrapper.getBlocksAt(null, BlockPositionIterator.fromMinMax(0, 0, 0, 1, 1, 1));
        assertArrayEquals(new int[] {32, 32, 32, 32, 33, 33, 33, 33}, blocks);
        assertEquals(8, world.readYs.size());
    }

    @Test
    void batchReadsOfTheSessionCacheAreNotShifted() {
        RecordingWorld world = new RecordingWorld(false);
        ShiftedWorldManager wrapper = cacheWorld(world, new AtomicInteger(16));

        assertArrayEquals(new int[] {0, 1}, wrapper.getBlocksAt(null, BlockPositionIterator.fromMinMax(0, 0, 0, 0, 1, 0)));
        assertEquals(List.of(0, 1), world.readYs);
    }

    @Test
    void batchReadsWithoutOffsetUseTheDelegatePath() {
        RecordingWorld world = new RecordingWorld(true);
        ShiftedWorldManager wrapper = realWorld(world, new AtomicInteger(0));

        assertArrayEquals(new int[] {0, 1}, wrapper.getBlocksAt(null, BlockPositionIterator.fromMinMax(0, 0, 0, 0, 1, 0)));
        assertEquals(List.of(0, 1), world.readYs);
    }
}
