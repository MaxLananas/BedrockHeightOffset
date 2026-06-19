package fr.buildtheearth.bedrockheightoffset.core;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
public class PlayerOffsetData {

    // Bedrock window constants
    public static final int BEDROCK_MIN   = -64;
    public static final int BEDROCK_MAX   = 320;
    public static final int BEDROCK_CENTER = 128; // BEDROCK_MIN + 384/2

    private final UUID uuid;
    private final String name;
    private final boolean bedrockPlayer;

    @Setter private volatile int offset;
    @Setter private volatile double lastJavaY;
    @Setter private volatile long lastOffsetChange;

    @Getter private volatile int offsetChangeCount;

    public PlayerOffsetData(UUID uuid, String name, boolean bedrockPlayer, double initialJavaY) {
        this.uuid = uuid;
        this.name = name;
        this.bedrockPlayer = bedrockPlayer;
        this.lastJavaY = initialJavaY;
        this.offset = computeOffset(initialJavaY);
        this.lastOffsetChange = System.currentTimeMillis();
        this.offsetChangeCount = 0;
    }

    public double toBedrockY(double javaY) {
        return javaY - offset;
    }

    public double toJavaY(double bedrockY) {
        return bedrockY + offset;
    }

    /**
     * Updates the offset based on the new Java Y position.
     * @return true if the offset actually changed
     */
    public boolean updateOffset(double newJavaY) {
        int newOffset = computeOffset(newJavaY);
        if (newOffset != this.offset) {
            this.offset = newOffset;
            this.lastOffsetChange = System.currentTimeMillis();
            this.offsetChangeCount++;
            return true;
        }
        return false;
    }

    /**
     * Returns true if the player's Bedrock Y is approaching either edge of the sliding window.
     */
    public boolean needsOffsetUpdate(int upperTrigger, int lowerTrigger) {
        double bedrockY = toBedrockY(lastJavaY);
        return bedrockY > upperTrigger || bedrockY < lowerTrigger;
    }

    /**
     * Computes the optimal offset to center the player inside the Bedrock window.
     * Result is always a multiple of 16 (section-aligned).
     *
     * offset = round(javaY - BEDROCK_CENTER) aligned to nearest 16
     * bedrockY = javaY - offset  =>  ≈ BEDROCK_CENTER
     */
    public static int computeOffset(double javaY) {
        int raw = (int) Math.round(javaY) - BEDROCK_CENTER;
        int aligned = (int) (Math.round((double) raw / 16.0) * 16);

        // Clamp so bedrockY stays within [-64, 320]
        int bedrockY = (int) Math.round(javaY) - aligned;
        if (bedrockY < BEDROCK_MIN) {
            aligned = ((int) Math.round(javaY) - BEDROCK_MIN) & ~0xF; // floor to multiple of 16
        } else if (bedrockY > BEDROCK_MAX) {
            aligned = (int) Math.ceil(((double)((int) Math.round(javaY) - BEDROCK_MAX)) / 16.0) * 16;
        }

        return aligned;
    }

    @Override
    public String toString() {
        return String.format("PlayerOffsetData{name=%s, offset=%d, javaY=%.1f, bedrockY=%.1f, changes=%d}",
            name, offset, lastJavaY, toBedrockY(lastJavaY), offsetChangeCount);
    }
}