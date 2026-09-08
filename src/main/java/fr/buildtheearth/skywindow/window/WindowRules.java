package fr.buildtheearth.skywindow.window;

/**
 * Pure coordinate math for the per-player height window, shared by the packet pipeline and mirrored
 * method-for-method by dev/verify/oracle.py.
 *
 * <p>Definitions:</p>
 * <ul>
 *   <li>Real space - Y coordinates as used by the Java server.</li>
 *   <li>Client space - what the Bedrock client believes; {@code clientY = realY - offset}.</li>
 *   <li>The offset is always a non-negative multiple of 16 so Java sections map onto exactly one
 *       Bedrock subchunk slot each; block X/Z and every relative quantity (deltas, motion, velocity,
 *       cursors, offsets) are never translated.</li>
 * </ul>
 *
 * <p>The client window is the Bedrock session's negotiated dimension: extended-height clients accept
 * [-512, 512), vanilla-era clients [0, 320), legacy [0, 256). Content that fits inside that window
 * needs no translation at all, which makes the extension a zero-work pass-through on ordinary servers.</p>
 */
public final class WindowRules {
    public static final int SECTION = 16;

    private WindowRules() {
    }

    /**
     * Highest placeable real block Y given the window policy: top client block + the cap offset,
     * never above the world's own last block.
     */
    public static int reachableBlockTop(int javaMaxY, int clientMinY, int clientHeight, int maxOffset) {
        return Math.min(javaMaxY - 1, clientMinY + clientHeight - 1 + Math.max(0, maxOffset));
    }

    public static int floorToSection(int y) {
        return Math.floorDiv(y, SECTION) * SECTION;
    }

    /**
     * Whether the real dimension [javaMinY, javaMaxY) exceeds the client window
     * [clientMinY, clientMinY + clientHeight) at either end. False means every coordinate is
     * already valid for the client and the offset must stay 0.
     */
    public static boolean windowingNeeded(int javaMinY, int javaMaxY, int clientMinY, int clientHeight) {
        return javaMinY < clientMinY || javaMaxY - 1 > clientMinY + clientHeight - 1;
    }

    /**
     * Largest offset that still keeps the top buildable layer (javaMaxY - 1) inside the window.
     * Offsets above this value would sink the world's roof below the client floor without making
     * any new area reachable, so they are never chosen.
     */
    public static int maxUsefulOffset(int javaMaxY, int clientMinY, int clientHeight) {
        return floorToSection(javaMaxY - clientMinY - clientHeight);
    }

    /**
     * Offset that centers the window around {@code realY}: the window's client-space center is
     * {@code clientMinY + clientHeight / 2}, so an offset of {@code realY - center} puts the player
     * in the middle of the window and defers the next switch as long as possible. This centering is
     * also what makes switches naturally rare (once per ~450 climbed blocks at full window height)
     * without any hysteresis bookkeeping.
     */
    public static int targetOffset(double realY, int clientMinY, int clientHeight, int maxOffset) {
        if (maxOffset <= 0) {
            return 0;
        }
        double centered = realY - (clientMinY + clientHeight / 2.0);
        int snapped = (int) Math.floor(centered / SECTION + 0.5) * SECTION;
        return Math.max(0, Math.min(maxOffset, snapped));
    }

    /**
     * A switch is requested when the player's feet approach the top or bottom edge of the window
     * (within {@code margin}) AND a different offset would actually help.
     */
    public static boolean shouldSwitch(double realY, int currentOffset, int clientMinY, int clientHeight,
                                       int margin, int maxOffset) {
        double clientY = realY - currentOffset;
        boolean nearTop = clientY >= clientMinY + clientHeight - margin;
        boolean nearBottom = currentOffset > 0 && clientY <= clientMinY + margin;
        if (!nearTop && !nearBottom) {
            return false;
        }
        return targetOffset(realY, clientMinY, clientHeight, maxOffset) != currentOffset;
    }

}
