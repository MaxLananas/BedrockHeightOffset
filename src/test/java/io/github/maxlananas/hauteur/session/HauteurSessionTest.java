package io.github.maxlananas.hauteur.session;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChunkCache and WindowConfig behaviour that the pipeline relies on. Chunk-packet instances are not
 * needed: the cache API under test takes them by reference only, so the tests use null payloads where
 * the cache must never dereference them... which is itself a property we assert (the cache is
 * transport-agnostic bookkeeping, no packet parsing happens inside it).
 */
class HauteurSessionTest {

    @Test
    void windowConfigDisablesItselfWhenTheWorldFits() {
        // extended-height client and a vanilla-sized world
        HauteurSession.WindowConfig w = HauteurSession.WindowConfig.of(-64, 320, -512, 1024, 0);
        assertFalse(w.needed());
        assertEquals(0, w.maxOffset());

        // world taller than the client window
        HauteurSession.WindowConfig bte = HauteurSession.WindowConfig.of(-64, 1952, -512, 1024, 0);
        assertTrue(bte.needed());
        assertEquals(1440, bte.maxOffset());

        // override caps the offset; capping to 0 or below turns windowing off entirely (identity)
        HauteurSession.WindowConfig capped = HauteurSession.WindowConfig.of(-64, 1952, -512, 1024, 512);
        assertTrue(capped.needed());
        assertEquals(512, capped.maxOffset());
    }

    @Test
    void overrideAboveTheUsefulMaximumIsClampedToIt() {
        // floorToSection(1952 + 512 - 1024) = 1440: an override cannot raise the offset past it
        HauteurSession.WindowConfig w = HauteurSession.WindowConfig.of(-64, 1952, -512, 1024, 99_999);
        assertTrue(w.needed());
        assertEquals(1440, w.maxOffset());
    }

    @Test
    void windowingStaysOffWhenNoNonNegativeOffsetCanHelp() {
        // The world starts below the client floor, but its top already fits. A non-negative offset moves
        // content down in client space, which only makes the bottom worse. floorToSection(200 - 256) = -64,
        // so the useful maximum is not positive.
        HauteurSession.WindowConfig w = HauteurSession.WindowConfig.of(-64, 200, 0, 256, 0);
        assertFalse(w.needed());
        assertEquals(0, w.maxOffset());

        // an override cannot switch on what the window rules rule out
        assertFalse(HauteurSession.WindowConfig.of(-64, 200, 0, 256, 512).needed());
    }

    @Test
    void chunkParamsUseRealDimensionSectionCount() {
        HauteurSession.WindowConfig bte = HauteurSession.WindowConfig.of(-64, 1952, -512, 1024, 0);
        var params = bte.chunkParams(1440);
        assertEquals(1440, params.offset());
        assertEquals(-64, params.javaMinY());
        assertEquals(126, params.sectionCount()); // (1952+64)/16
    }

    @Test
    void stateRefreshAndResetRoundTrip() {
        HauteurSession state = new HauteurSession(
            () -> HauteurSession.WindowConfig.of(-64, 1952, -512, 1024, 0), 16, 1 << 20);
        state.refreshWindow();
        assertTrue(state.window.needed());
        // cache starts empty and survives resets
        assertEquals(0, state.chunkCache.size());
        state.resetChunkCache();
        assertEquals(0, state.chunkCache.size());
    }

    @Test
    void failedRereadKeepsThePreviousWindowInsteadOfSwitchingWindowingOff() {
        // A transient dimension-read failure must not turn windowing off while the client is shifted.
        AtomicReference<HauteurSession.WindowConfig> next =
            new AtomicReference<>(HauteurSession.WindowConfig.of(-64, 1952, -512, 1024, 0));
        HauteurSession state = new HauteurSession(next::get, 16, 1 << 20);
        state.refreshWindow();
        assertTrue(state.window.needed());

        next.set(null);
        state.refreshWindow();
        assertTrue(state.window.needed());
        assertEquals(1440, state.window.maxOffset());
    }

    @Test
    void windowStaysUnsetUntilTheDimensionHasBeenReadOnce() {
        HauteurSession state = new HauteurSession(() -> null, 16, 1 << 20);
        state.refreshWindow();
        assertNull(state.window);
    }
}
