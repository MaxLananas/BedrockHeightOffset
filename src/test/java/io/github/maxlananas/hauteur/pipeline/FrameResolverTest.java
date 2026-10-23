package io.github.maxlananas.hauteur.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameResolverTest {

    private static final double BOUND = FrameResolver.travelBound(0);

    @Test
    void movementIsAttributedToTheFrameItIsPlausibleIn() {
        // Packet written under offset 0 at real Y 100; the switch moved the offset to 448.
        assertEquals(FrameResolver.Frame.PREVIOUS,
            FrameResolver.movement(100, 100, 0, 448, BOUND));
        // Packet written under offset 448 at client Y -348, i.e. real Y 100 again.
        assertEquals(FrameResolver.Frame.CURRENT,
            FrameResolver.movement(100, -348, 0, 448, BOUND));
    }

    @Test
    void ambiguousMovementIsDroppedNotGuessed() {
        // Both candidates (100 and 116) are within the travel bound of the anchor.
        assertEquals(FrameResolver.Frame.NONE, FrameResolver.movement(100, 100, 0, 16, BOUND));
    }

    @Test
    void movementMatchingNeitherFrameIsDropped() {
        // Candidates 1000 and 1448, anchor 100: nothing is plausible.
        assertEquals(FrameResolver.Frame.NONE, FrameResolver.movement(100, 1000, 0, 448, BOUND));
    }

    @Test
    void travelBoundGrowsWithFreezeDurationButStartsAtTheBase() {
        assertEquals(FrameResolver.BASE_TRAVEL_BLOCKS, FrameResolver.travelBound(0), 1e-9);
        assertTrue(FrameResolver.travelBound(5_000_000_000L) > FrameResolver.travelBound(400_000_000L));
        assertEquals(FrameResolver.BASE_TRAVEL_BLOCKS, FrameResolver.travelBound(-5), 1e-9,
            "a non-monotonic clock must never shrink the bound below the base");
    }

    @Test
    void blockActionUsesInteractionReachNotTravel() {
        // Block at real Y 100 under offset 0 is within reach of a feet anchor at 100.
        assertEquals(FrameResolver.Frame.PREVIOUS, FrameResolver.blockAction(100, 100, 0, 448));
        // Same packet read under offset 448: block client Y -348 is real Y 100.
        assertEquals(FrameResolver.Frame.CURRENT, FrameResolver.blockAction(100, -348, 0, 448));
        // Far from both frames.
        assertEquals(FrameResolver.Frame.NONE, FrameResolver.blockAction(100, 500, 0, 448));
    }

    /**
     * Property: when the anchor is near the packet's true position and the switch delta is at least
     * 64 blocks, the resolver returns the true frame. It never returns the wrong frame for any delta.
     */
    @Test
    void resolverNeverPicksTheWrongFrame() {
        Random rnd = new Random(20260923L);
        for (int trial = 0; trial < 20_000; trial++) {
            int previous = rnd.nextInt(0, 64) * 16;
            int delta = (rnd.nextBoolean() ? 1 : -1) * rnd.nextInt(1, 128) * 16;
            int current = previous + delta;
            if (current < 0) {
                continue;
            }
            double realY = rnd.nextInt(-64, 2000) + rnd.nextDouble();
            boolean trueFrameIsPrevious = rnd.nextBoolean();
            int trueOffset = trueFrameIsPrevious ? previous : current;
            double clientY = realY - trueOffset;
            double anchor = realY + (rnd.nextDouble() * 2 - 1) * 10.0; // anchor within 10 blocks of truth

            FrameResolver.Frame resolved = FrameResolver.movement(anchor, clientY, previous, current, BOUND);
            FrameResolver.Frame expected = trueFrameIsPrevious ? FrameResolver.Frame.PREVIOUS : FrameResolver.Frame.CURRENT;
            if (Math.abs(delta) >= 64) {
                assertEquals(expected, resolved, "delta " + delta + " realY " + realY);
            } else {
                assertNotEquals(trueFrameIsPrevious ? FrameResolver.Frame.CURRENT : FrameResolver.Frame.PREVIOUS,
                    resolved, "a small delta must never resolve to the wrong frame");
            }
        }
    }
}
