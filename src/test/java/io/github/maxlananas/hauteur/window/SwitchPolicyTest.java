package io.github.maxlananas.hauteur.window;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchPolicyTest {
    // 256-high vanilla-style window starting at 0, margin 64 (the default), cooldown 2000 ms.
    private static final int C_MIN = 0;
    private static final int C_H = 256;
    private static final int MARGIN = 64;
    private static final int MAX_OFF = 1776;
    private static final long COOLDOWN = 2000;

    private static boolean request(double realY, int offset, long now, long last, int backoff, boolean auth) {
        return SwitchPolicy.shouldRequest(realY, offset, C_MIN, C_H, MARGIN, MAX_OFF, auth, now, last, backoff,
            COOLDOWN);
    }

    @Test
    void clientOutsideTheTopBypassesTheCooldownAndTheBackoff() {
        // Feet at 400 with offset 144: the client is at 256, one block above its window. It must switch now.
        assertTrue(request(400, 144, 10_000 + 10, 10_000, 8, false));
    }

    @Test
    void insideTheWindowTheCooldownStillApplies() {
        // Feet at 200 with offset 0: near the top, but the client still sees the block, so the cooldown holds.
        assertFalse(request(200, 0, 10_000 + 10, 10_000, 1, false));
        assertTrue(request(200, 0, 10_000 + COOLDOWN, 10_000, 1, false));
    }

    @Test
    void theBackoffMultipliesTheCooldownInsideTheWindow() {
        assertFalse(request(200, 0, 10_000 + COOLDOWN + 1, 10_000, 2, false));
        assertTrue(request(200, 0, 10_000 + 2 * COOLDOWN, 10_000, 2, false));
    }

    @Test
    void aServerTeleportBypassesTheCooldown() {
        assertTrue(request(200, 0, 10_000 + 1, 10_000, 8, true));
    }

    @Test
    void belowTheWindowFloorTheSwitchIsNotBypassedBecauseItCannotHelp() {
        // Below clientMin with offset 64 the switch down to 0 is still outside, so a bypass would only churn.
        assertFalse(request(-30, 64, 10_000 + 10, 10_000, 1, false));
    }

    @Test
    void noBypassWhenTheTargetIsAlreadyReached() {
        // Outside the top, but the world-top clamp already holds the offset at its maximum: nothing to switch to.
        assertFalse(SwitchPolicy.shouldRequest(1900, 1600, C_MIN, C_H, MARGIN, 1600, false, 10_010, 10_000, 1,
            COOLDOWN));
    }

    @Test
    void backoffDoublesWithinFourCooldownsCapsAtEightAndResets() {
        assertEquals(2, SwitchPolicy.nextBackoff(1_000, 0, 1, COOLDOWN));
        assertEquals(8, SwitchPolicy.nextBackoff(1_000, 0, 8, COOLDOWN));
        assertEquals(1, SwitchPolicy.nextBackoff(4 * COOLDOWN, 0, 4, COOLDOWN));
    }

    @Test
    void repeatedSwitchesKeepTheBackoffBounded() {
        // A flyer switching necessarily keeps raising the backoff; it must stay within the cap.
        int backoff = 1;
        for (int i = 0; i < 20; i++) {
            backoff = SwitchPolicy.nextBackoff(i, 0, backoff, COOLDOWN);
        }
        assertTrue(backoff <= SwitchPolicy.MAX_BACKOFF);
    }

    /**
     * Invariants over random states, with a fixed seed: a request always has a window reason; inside the cooldown
     * and inside the window, a non-authoritative request is never made; the backoff stays in 1..MAX and doubles
     * only within four cooldowns, otherwise it resets.
     */
    @Test
    void policyInvariantsHoldOverRandomStates() {
        java.util.Random rnd = new java.util.Random(21);
        for (int trial = 0; trial < 20_000; trial++) {
            int clientMinY = -512 + 16 * rnd.nextInt(64);
            int clientHeight = 16 * (1 + rnd.nextInt(64));
            int margin = 32 + rnd.nextInt(225);
            int offset = 16 * rnd.nextInt(80);
            int maxOffset = 16 * rnd.nextInt(120);
            double realY = -700 + rnd.nextDouble() * 1600;
            boolean authoritative = rnd.nextInt(5) == 0;
            long cooldown = 250 + rnd.nextInt(5000);
            int backoff = 1 + rnd.nextInt(SwitchPolicy.MAX_BACKOFF);
            long now = rnd.nextInt(20_000);
            long last = now - rnd.nextInt(30_000);

            boolean request = SwitchPolicy.shouldRequest(realY, offset, clientMinY, clientHeight, margin, maxOffset,
                authoritative, now, last, backoff, cooldown);
            if (request) {
                assertTrue(WindowRules.shouldSwitch(realY, offset, clientMinY, clientHeight, margin, maxOffset),
                    "a request needs a window reason at realY=" + realY);
            }
            boolean inCooldown = now - last < cooldown * Math.max(1, backoff);
            boolean outside = realY >= clientMinY
                && SwitchPolicy.clientOutsideWindow(realY, offset, clientMinY, clientHeight);
            if (!authoritative && inCooldown && !outside) {
                assertFalse(request, "no request inside the cooldown while the client is inside its window");
            }

            int next = SwitchPolicy.nextBackoff(now, last, backoff, cooldown);
            assertTrue(next >= 1 && next <= SwitchPolicy.MAX_BACKOFF, "backoff in range: " + next);
            if (now - last < cooldown * 4) {
                assertEquals(Math.min(SwitchPolicy.MAX_BACKOFF, backoff * 2), next);
            } else {
                assertEquals(1, next);
            }
        }
    }
}
