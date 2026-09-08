package fr.buildtheearth.skywindow.window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowRulesTest {
    // Extended-height overworld client: Y [-512, 512), BTE-like server up to 1952.
    private static final int C_MIN = -512;
    private static final int C_H = 1024;
    private static final int J_MIN = -64;
    private static final int J_MAX = 1952; // exclusive top like a 1952 build limit
    private static final int MAX_OFF = WindowRules.maxUsefulOffset(J_MAX, C_MIN, C_H);

    @Test
    void maxUsefulOffsetLandsOn1440ForBteHeights() {
        // 1952 - (-512) - 1024 = 1440, already section-aligned; standing at 1952 maps to client Y 512,
        // the walkable block above the highest placeable one (511).
        assertEquals(1440, MAX_OFF);
    }

    @Test
    void vanillaSizedWorldNeedsNoWindowing() {
        assertFalse(WindowRules.windowingNeeded(-64, 320, -64, 384)); // legacy client, exactly fits
        assertFalse(WindowRules.windowingNeeded(-64, 320, -512, 1024)); // extended client
        assertFalse(WindowRules.windowingNeeded(0, 128, 0, 256)); // nether
        assertFalse(WindowRules.windowingNeeded(0, 256, 0, 256)); // end
        assertTrue(WindowRules.windowingNeeded(-64, 321, -64, 384)); // one block over the top
        assertTrue(WindowRules.windowingNeeded(-64, 1952, -512, 1024)); // BTE
    }

    @Test
    void targetOffsetIsAlignedBoundedAndCentering() {
        for (int realY = J_MIN; realY <= J_MAX; realY++) {
            int o = WindowRules.targetOffset(realY, C_MIN, C_H, MAX_OFF);
            assertEquals(0, o % 16, "not section-aligned at realY " + realY);
            assertTrue(o >= 0, "negative offset at " + realY);
            assertTrue(o <= MAX_OFF, "offset past max at " + realY);
            int clientY = realY - o;
            // The exclusive top edge (feet exactly at javaMaxY) may land on the window's exclusive
            // ceiling: standing on the highest block puts the player in the "one above build limit"
            // air slot; the client clamps that transient position and it self-corrects with gravity.
            assertTrue(clientY > C_MIN && clientY <= C_MIN + C_H,
                "player escapes window at realY " + realY + " -> clientY " + clientY);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-64, 0, 319, 320, 510, 1000, 1500, 1951, 1952})
    void roundTripOfTheChosenWindow(int realY) {
        int o = WindowRules.targetOffset(realY, C_MIN, C_H, MAX_OFF);
        double clientY = realY - o;
        assertEquals(realY, clientY + o, 0.0); // exact: offsets are integral block counts
    }

    @ParameterizedTest
    @CsvSource({
        // realY, current offset, must switch?
        "480, 0, true",   // touching top edge of home window (>= 512-32)
        "440, 0, false",  // inside, and even though a different offset exists, no need
        "-512, 0, false", // at the very bottom with offset 0 there is no lower offset to switch to
        "1952, 1440, false", // stable at the very top: offset 1440 keeps clientY=512 < 512? clientY 512 => top edge -> but target==current
        "-64, 1440, true", // stranded at the world floor from a stale high offset
    })
    void switchDecisionMatrix(int realY, int currentOffset, boolean shouldSwitch) {
        assertEquals(shouldSwitch,
            WindowRules.shouldSwitch(realY, currentOffset, C_MIN, C_H, 32, MAX_OFF),
            "decision at realY=" + realY + " offset=" + currentOffset);
    }

    @Test
    void climbingPlayerSwitchesRarelyAndNeverOscillates() {
        int offset = 0;
        int switches = 0;
        int lastY = 0;
        for (int realY = -64; realY <= 1952; realY++) { // steady climb
            if (WindowRules.shouldSwitch(realY, offset, C_MIN, C_H, 64, MAX_OFF)) {
                int target = WindowRules.targetOffset(realY, C_MIN, C_H, MAX_OFF);
                assertNotEquals(offset, target);
                assertNotEquals(realY, lastY);
                offset = target;
                switches++;
            }
            lastY = realY;
        }
        // 2016 climbed blocks; each window covers ~960 blocks of travel before the margin is hit.
        assertTrue(switches >= 1 && switches <= 5, "too many switches while climbing: " + switches);
        assertEquals(1440, offset, "should end pinned at the top of the world");
    }

    @Test
    void hoveringNearTheEdgeDoesNotFlicker() {
        int offset = 0;
        int switches = 0;
        for (int i = 0; i < 4000; i++) { // 200s of movement jitter around Y 470..490
            double realY = 480 + 10 * Math.sin(i * 0.31);
            if (WindowRules.shouldSwitch(realY, offset, C_MIN, C_H, 64, MAX_OFF)) {
                offset = WindowRules.targetOffset(realY, C_MIN, C_H, MAX_OFF);
                switches++;
            }
        }
        assertTrue(switches <= 1, "window flickered " + switches + " times while hovering");
    }

    @Test
    void reachableTopMatchesClientCeilingPlusCap() {
        // BTE-shaped: extended client ceiling 511 + offset cap 1440 = 1951, never above the world top.
        assertEquals(1951, WindowRules.reachableBlockTop(J_MAX, C_MIN, C_H, MAX_OFF));
        assertEquals(1511, WindowRules.reachableBlockTop(J_MAX, C_MIN, C_H, 1000));
        // when the whole world is below the natural ceiling, the world top wins, not the window math
        assertEquals(319, WindowRules.reachableBlockTop(320, C_MIN, C_H, 0));
    }

    @Test
    void floorToSectionHandlesNegatives() {
        assertEquals(-16, WindowRules.floorToSection(-1));
        assertEquals(0, WindowRules.floorToSection(15));
        assertEquals(1440, WindowRules.floorToSection(1441));
    }
}
