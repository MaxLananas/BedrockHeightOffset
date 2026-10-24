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
}
