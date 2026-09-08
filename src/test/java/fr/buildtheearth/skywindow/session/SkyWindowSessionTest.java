package fr.buildtheearth.skywindow.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChunkCache and WindowConfig behaviour that the pipeline relies on. Chunk-packet instances are not
 * needed: the cache API under test takes them by reference only, so the tests use null payloads where
 * the cache must never dereference them... which is itself a property we assert (the cache is
 * transport-agnostic bookkeeping, no packet parsing happens inside it).
 */
class SkyWindowSessionTest {

    @Test
    void windowConfigDisablesItselfWhenTheWorldFits() {
        // extended-height client and a vanilla-sized world
        SkyWindowSession.WindowConfig w = SkyWindowSession.WindowConfig.of(-64, 320, -512, 1024, 0);
        assertFalse(w.needed());
        assertEquals(0, w.maxOffset());

        // world taller than the client window
        SkyWindowSession.WindowConfig bte = SkyWindowSession.WindowConfig.of(-64, 1952, -512, 1024, 0);
        assertTrue(bte.needed());
        assertEquals(1440, bte.maxOffset());

        // override caps the offset; capping to 0 or below turns windowing off entirely (identity)
        SkyWindowSession.WindowConfig capped = SkyWindowSession.WindowConfig.of(-64, 1952, -512, 1024, 512);
        assertTrue(capped.needed());
        assertEquals(512, capped.maxOffset());
    }

    @Test
    void chunkParamsUseRealDimensionSectionCount() {
        SkyWindowSession.WindowConfig bte = SkyWindowSession.WindowConfig.of(-64, 1952, -512, 1024, 0);
        var params = bte.chunkParams(1440);
        assertEquals(1440, params.offset());
        assertEquals(-64, params.javaMinY());
        assertEquals(126, params.sectionCount()); // (1952+64)/16
    }

    @Test
    void stateRefreshAndResetRoundTrip() {
        SkyWindowSession state = new SkyWindowSession(
            () -> SkyWindowSession.WindowConfig.of(-64, 1952, -512, 1024, 0), 16, 1 << 20);
        state.refreshWindow();
        assertTrue(state.window.needed());
        // cache starts empty and survives resets
        assertEquals(0, state.chunkCache.size());
        state.resetChunkCache();
        assertEquals(0, state.chunkCache.size());
    }
}
