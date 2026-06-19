package fr.buildtheearth.bedrockheightoffset.core;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
public class PlayerOffsetData {

    public static final int BEDROCK_MIN    = -64;
    public static final int BEDROCK_MAX    = 320;
    public static final int BEDROCK_HEIGHT = 384;
    public static final int BEDROCK_CENTER = BEDROCK_MIN + BEDROCK_HEIGHT / 2; // 128

    public static final int JAVA_MIN = -64;
    public static final int JAVA_MAX = 1952;

    // 64-block margin from each edge before triggering offset recalculation
    public static final int UPPER_TRIGGER = BEDROCK_MAX - 64; // 256
    public static final int LOWER_TRIGGER = BEDROCK_MIN + 64; // 0

    private final UUID    uuid;
    private final String  name;
    private final boolean bedrockPlayer;

    @Setter private volatile int    offset;
    @Setter private volatile double lastJavaY;
    @Setter private volatile long   lastOffsetChange;
    private volatile int            offsetChangeCount;

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
        double by = toBedrockY(lastJavaY);
        return by > upperTrigger || by < lowerTrigger;
    }

    public static int computeOffset(double javaY) {
        double clamped = Math.max(JAVA_MIN, Math.min(JAVA_MAX, javaY));
        int raw        = (int) Math.round(clamped) - BEDROCK_CENTER;
        int aligned    = (int) (Math.round((double) raw / 16.0) * 16);

        // Clamp so bedrockY = javaY - aligned stays within [BEDROCK_MIN, BEDROCK_MAX]
        int bedrockY = (int) Math.round(clamped) - aligned;
        if (bedrockY < BEDROCK_MIN) {
            aligned = ((int) Math.round(clamped) - BEDROCK_MIN) & ~0xF;
        } else if (bedrockY > BEDROCK_MAX) {
            aligned = (int) Math.ceil(((double)((int) Math.round(clamped) - BEDROCK_MAX)) / 16.0) * 16;
        }
        return aligned;
    }

    public double currentBedrockY()  { return toBedrockY(lastJavaY); }
    public int    offsetSections()   { return offset >> 4; }

    @Override
    public String toString() {
        return String.format(
            "PlayerOffsetData{name=%s, offset=%d (%d sec), jY=%.1f, bY=%.1f, changes=%d}",
            name, offset, offsetSections(), lastJavaY, currentBedrockY(), offsetChangeCount);
    }
}