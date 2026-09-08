package fr.buildtheearth.skywindow.translate;

import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WindowedChunksTest {

    private static WindowedChunks.Params params(int offset) {
        // BTE-like: real Y [-64, 1952) => 126 sections.
        return WindowedChunks.Params.of(offset, -64, 1952 - (-64));
    }

    @Test
    void offsetZeroChangesNothing() {
        BlockEntityInfo[] input = {new BlockEntityInfo(1, 100, 2, null, null)};
        assertNull(WindowedChunks.shiftBlockEntities(input, params(0)));
    }

    @Test
    void blockEntitiesMoveWithTheirBlocks() {
        BlockEntityInfo chest = new BlockEntityInfo(3, 1901, 7, null, null);
        BlockEntityInfo[] out = WindowedChunks.shiftBlockEntities(new BlockEntityInfo[] {chest}, params(1440));
        assertEquals(1, out.length);
        assertEquals(461, out[0].getY());
        assertEquals(3, out[0].getX());
        assertEquals(7, out[0].getZ());
    }

    @Test
    void blockEntitiesOutsideThePayloadArePruned() {
        // A chest at the world floor while the player is pinned to the top window: its shifted Y
        // would index Geyser's section array out of bounds, so it is dropped instead of crashing.
        BlockEntityInfo floorChest = new BlockEntityInfo(0, -60, 0, null, null);
        BlockEntityInfo roofChest = new BlockEntityInfo(0, 1900, 0, null, null);
        BlockEntityInfo[] out = WindowedChunks.shiftBlockEntities(
            new BlockEntityInfo[] {floorChest, roofChest}, params(1440));
        assertEquals(1, out.length);
        assertEquals(460, out[0].getY());
    }

    @Test
    void pruningBoundaryFollowsGeyserSectionArray() {
        // javaMinSection = -4; sections [0,126). Shifted Y must satisfy 0 <= (y>>4) + 4 < 126.
        // Boundary: y = -512 (clientMinY of extended overworld) indexes 32-? -> (−32)+4 = −28 → pruned
        // by the payload check? No: the payload spans real [-64,1952) windowed by 1440 = [1440-512?..].
        // Concretely for offset 1440: visible real Y range for blocks is [1376, 2400); floor content
        // below 1376 is invisible to the client and correctly pruned.
        BlockEntityInfo justBelow = new BlockEntityInfo(0, 1375, 0, null, null);
        BlockEntityInfo atBottom = new BlockEntityInfo(0, 1376, 0, null, null);
        BlockEntityInfo[] out = WindowedChunks.shiftBlockEntities(
            new BlockEntityInfo[] {justBelow, atBottom}, params(1440));
        assertEquals(1, out.length);
        assertEquals(1376 - 1440, out[0].getY());
    }
}
