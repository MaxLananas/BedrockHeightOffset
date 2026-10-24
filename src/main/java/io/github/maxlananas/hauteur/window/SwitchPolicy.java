package io.github.maxlananas.hauteur.window;

/**
 * Decides whether a window switch is requested now, and how the anti-oscillation backoff evolves. Pure: the handler
 * supplies the clock and the state, so the rules can be tested and simulated without a channel.
 *
 * <p>The cooldown exists to stop a player who hovers at the window edge from switching again and again. It must never
 * delay a switch that the player needs: when the client is already outside its window, the world is missing from the
 * view, so the switch is not deferred. Measured by simulating a player climbing at 40 blocks per second in a 256-high
 * window, the cooldown with backoff left the client outside for about 34 of 120 seconds; bypassing it for that case
 * reduced this to 0, at a cost of about 3 seconds of extra freeze over the same 120 seconds.</p>
 */
public final class SwitchPolicy {

    /** The backoff multiplier never exceeds this. */
    public static final int MAX_BACKOFF = 8;

    private SwitchPolicy() {
    }

    /**
     * Whether the client's view misses content at this real Y: the client-space Y is outside
     * {@code [clientMinY, clientMinY + clientHeight)}.
     */
    public static boolean clientOutsideWindow(double realY, int offset, int clientMinY, int clientHeight) {
        double clientY = realY - offset;
        return clientY < clientMinY || clientY >= clientMinY + clientHeight;
    }

    /**
     * Whether a switch should be requested now.
     *
     * @param authoritative     true for a server teleport, which bypasses the cooldown
     * @param nowMs             current time in milliseconds
     * @param lastSwitchMs      time of the previous switch of this player
     * @param backoff           current backoff multiplier, 1 to {@link #MAX_BACKOFF}
     * @param cooldownMs        configured minimum time between two switches
     */
    public static boolean shouldRequest(double realY, int offset, int clientMinY, int clientHeight, int margin,
                                        int maxOffset, boolean authoritative, long nowMs, long lastSwitchMs,
                                        int backoff, long cooldownMs) {
        if (!WindowRules.shouldSwitch(realY, offset, clientMinY, clientHeight, margin, maxOffset)) {
            return false;
        }
        if (authoritative) {
            return true;
        }
        // Only a position above the window floor can be brought back by a switch. Below the floor with no offset,
        // the switch would be a no-op, and the cooldown applies as usual.
        if (realY >= clientMinY && clientOutsideWindow(realY, offset, clientMinY, clientHeight)) {
            return true;
        }
        return nowMs - lastSwitchMs >= cooldownMs * Math.max(1, backoff);
    }

    /**
     * Whether a manual re-home ({@code /hauteur window}) may switch now. It is not an automatic switch and needs no
     * position check, but every switch resends the cached chunks, so it waits out the same cooldown, times the backoff.
     */
    public static boolean manualAllowed(long nowMs, long lastSwitchMs, int backoff, long cooldownMs) {
        return nowMs - lastSwitchMs >= cooldownMs * Math.max(1, backoff);
    }

    /**
     * The backoff after a switch: doubled when the previous switch was less than four cooldowns ago, otherwise reset.
     */
    public static int nextBackoff(long nowMs, long lastSwitchMs, int backoff, long cooldownMs) {
        return nowMs - lastSwitchMs < cooldownMs * 4 ? Math.min(MAX_BACKOFF, backoff * 2) : 1;
    }
}
