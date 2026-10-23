package io.github.maxlananas.hauteur.world;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.geyser.level.WorldManager;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShiftedWorldManagerTest {

    /** Real-space world stand-in that records the Y it was asked for and returns it as the block id. */
    static final class RecordingWorld extends WorldManager {
        final List<Integer> readYs = new ArrayList<>();
        final List<Integer> potYs = new ArrayList<>();

        @Override
        public int getBlockAt(GeyserSession session, int x, int y, int z) {
            readYs.add(y);
            return y;
        }

        @Override
        public boolean hasOwnChunkCache() {
            return true;
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

    private static ShiftedWorldManager wrap(RecordingWorld world, AtomicInteger offset) {
        return new ShiftedWorldManager(world, session -> offset.get());
    }

    @Test
    void zeroOffsetPassesCoordinatesThrough() {
        RecordingWorld world = new RecordingWorld();
        ShiftedWorldManager wrapper = wrap(world, new AtomicInteger(0));

        assertEquals(70, wrapper.getBlockAt(null, 1, 70, 2));
        assertEquals(List.of(70), world.readYs);
    }

    @Test
    void readsAreShiftedByTheWindowOffset() {
        RecordingWorld world = new RecordingWorld();
        ShiftedWorldManager wrapper = wrap(world, new AtomicInteger(48));

        // Client Y -10 is real Y 38 under a 48 block offset (client Y = real Y - offset).
        assertEquals(38, wrapper.getBlockAt(null, 0, -10, 0));
        assertEquals(List.of(38), world.readYs);
    }

    @Test
    void vectorAndAsyncReadsUseTheSameShift() throws Exception {
        RecordingWorld world = new RecordingWorld();
        ShiftedWorldManager wrapper = wrap(world, new AtomicInteger(32));

        assertEquals(37, wrapper.getBlockAt(null, Vector3i.from(0, 5, 0)));
        assertEquals(37, (int) wrapper.getBlockAtAsync(null, 0, 5, 0).get());
        assertEquals(37, (int) wrapper.getBlockAtAsync(null, Vector3i.from(0, 5, 0)).get());
        assertEquals(List.of(37, 37, 37), world.readYs);
    }

    @Test
    void offsetIsReadOnEveryCall() {
        RecordingWorld world = new RecordingWorld();
        AtomicInteger offset = new AtomicInteger(0);
        ShiftedWorldManager wrapper = wrap(world, offset);

        assertEquals(10, wrapper.getBlockAt(null, 0, 10, 0));
        offset.set(64); // a window switch happened between two reads
        assertEquals(74, wrapper.getBlockAt(null, 0, 10, 0));
        assertEquals(List.of(10, 74), world.readYs);
    }

    @Test
    void decoratedPotLookupIsShifted() {
        RecordingWorld world = new RecordingWorld();
        ShiftedWorldManager wrapper = wrap(world, new AtomicInteger(16));

        wrapper.getDecoratedPotData(null, Vector3i.from(1, 4, 1), strings -> { });
        assertEquals(List.of(20), world.potYs);
    }

    @Test
    void coordinateFreeCallsReachTheDelegateUnchanged() {
        RecordingWorld world = new RecordingWorld();
        ShiftedWorldManager wrapper = wrap(world, new AtomicInteger(128));

        assertSame(world, wrapper.delegate());
        assertTrue(wrapper.hasOwnChunkCache());
    }
}
