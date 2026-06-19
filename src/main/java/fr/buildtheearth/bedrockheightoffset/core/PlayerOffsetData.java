package fr.buildtheearth.bedrockheightoffset.core;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
public class PlayerOffsetData {

    // Bedrock client hard limits (immutable)
    public static final int BEDROCK_MIN    = -64;
    public static final int BEDROCK_MAX    = 320;
    public static final int BEDROCK_HEIGHT = 384;
    public static final int BEDROCK_CENTER = BEDROCK_MIN + BEDROCK_HEIGHT / 2; // 128

    // BuildTheEarth terraplusminus world limits
    public static final int JAVA_MIN = -64;
    public static final int JAVA_MAX = 1952;

    // Sliding window safety margins (distance from edge before offset recalculation)
    // 64 blocks = 4 sections of margin on each side
    public static final int MARGIN = 64;
    public static final int UPPER_TRIGGER = BEDROCK_MAX - MARGIN; // 256
    public static final int LOWER_TRIGGER = BEDROCK_MIN + MARGIN; // 0

    private final UUID   uuid;
    private final String name;
    private final boolean bedrockPlayer;

    @Setter private volatile int    offset;
    @Setter private volatile double lastJavaY;
    @Setter private volatile long   lastOffsetChange;

    private volatile int offsetChangeCount;

    public PlayerOffsetData(UUID uuid, String name, boolean bedrockPlayer, double initialJavaY) {
        this.uuid              = uuid;
        this.name              = name;
        this.bedrockPlayer     = bedrockPlayer;
        this.lastJavaY         = initialJavaY;
        this.offset            = computeOffset(initialJavaY);
        this.lastOffsetChange  = System.currentTimeMillis();
        this.offsetChangeCount = 0;
    }

    public double toBedrockY(double javaY) { return javaY - offset; }
    public double toJavaY(double bedrockY) { return bedrockY + offset; }

    public boolean updateOffset(double newJavaY) {
        int newOffset = computeOffset(newJavaY);
        if (newOffset != this.offset) {
            this.offset           = newOffset;
            this.lastOffsetChange = System.currentTimeMillis();
            this.offsetChangeCount++;
            return true;
        }
        return false;
    }

    public boolean needsOffsetUpdate(int upperTrigger, int lowerTrigger) {
        double bedrockY = toBedrockY(lastJavaY);
        return bedrockY > upperTrigger || bedrockY < lowerTrigger;
    }

    /**
     * Computes the section-aligned Y offset to center the player within the Bedrock window.
     *
     * Formula:
     *   rawOffset = javaY - BEDROCK_CENTER
     *   aligned   = round(rawOffset / 16) * 16
     *
     * Then clamped so bedrockY = javaY - aligned stays within [BEDROCK_MIN, BEDROCK_MAX].
     *
     * Examples for JAVA_MAX=1952:
     *   javaY=1952 → rawOffset=1824 → aligned=1824 → bedrockY=128 ✓
     *   javaY=800  → rawOffset=672  → aligned=672  → bedrockY=128 ✓
     *   javaY=64   → rawOffset=-64  → aligned=-64  → bedrockY=128 ✓
     *   javaY=-64  → rawOffset=-192 → aligned=-192 → bedrockY=128 ✓
     */
    public static int computeOffset(double javaY) {
        // Clamp input to valid Java world range
        double clampedY = Math.max(JAVA_MIN, Math.min(JAVA_MAX, javaY));

        int raw     = (int) Math.round(clampedY) - BEDROCK_CENTER;
        int aligned = (int) (Math.round((double) raw / 16.0) * 16);

        // Clamp: ensure bedrockY stays within [BEDROCK_MIN, BEDROCK_MAX]
        int bedrockY = (int) Math.round(clampedY) - aligned;

        if (bedrockY < BEDROCK_MIN) {
            // bedrockY too low → reduce offset (shift window down)
            int needed = (int) Math.round(clampedY) - BEDROCK_MIN;
            aligned = (needed / 16) * 16; // floor to multiple of 16
        } else if (bedrockY > BEDROCK_MAX) {
            // bedrockY too high → increase offset (shift window up)
            int needed = (int) Math.round(clampedY) - BEDROCK_MAX;
            aligned = (int) Math.ceil((double) needed / 16.0) * 16;
        }

        return aligned;
    }

    /** Returns the current Bedrock Y of this player. */
    public double currentBedrockY() { return toBedrockY(lastJavaY); }

    /** Returns the sub-chunk section offset (offset in block units / 16). */
    public int offsetSections() { return offset >> 4; }

    @Override
    public String toString() {
        return String.format(
            "PlayerOffsetData{name=%s, offset=%d (%d sec), javaY=%.1f, bedrockY=%.1f, changes=%d}",
            name, offset, offsetSections(), lastJavaY, currentBedrockY(), offsetChangeCount
        );
    }
}