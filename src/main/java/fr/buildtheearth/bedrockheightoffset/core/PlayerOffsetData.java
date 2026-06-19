package fr.buildtheearth.bedrockheightoffset.core;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Données d'offset Y pour un joueur connecté.
 *
 * Concept sliding window :
 *   bedrockY = javaY - offset
 *   javaY    = bedrockY + offset
 *
 * L'offset est toujours un multiple de 16 (alignement sub-chunks).
 */
@Getter
public class PlayerOffsetData {

    private final UUID uuid;
    private final String name;

    /** L'offset actuel en blocs. Multiple de 16. */
    @Setter
    private volatile int offset;

    /** La dernière position Y Java connue. */
    @Setter
    private volatile double lastJavaY;

    /** True si le joueur est Bedrock (Floodgate). */
    private final boolean bedrockPlayer;

    /** Timestamp du dernier recalcul d'offset. */
    @Setter
    private volatile long lastOffsetChange;

    /** Nombre de recalculs d'offset depuis la connexion. */
    private volatile int offsetChangeCount;

    public PlayerOffsetData(UUID uuid, String name, boolean bedrockPlayer, double initialJavaY) {
        this.uuid = uuid;
        this.name = name;
        this.bedrockPlayer = bedrockPlayer;
        this.lastJavaY = initialJavaY;
        this.offset = computeOffset(initialJavaY);
        this.lastOffsetChange = System.currentTimeMillis();
        this.offsetChangeCount = 0;
    }

    /**
     * Convertit une coordonnée Y Java → Bedrock.
     */
    public double toBedrockY(double javaY) {
        return javaY - offset;
    }

    /**
     * Convertit une coordonnée Y Bedrock → Java.
     */
    public double toJavaY(double bedrockY) {
        return bedrockY + offset;
    }

    /**
     * Met à jour l'offset et incrémente le compteur.
     * @return true si l'offset a réellement changé
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
     * Vérifie si le joueur approche des bords de la fenêtre Bedrock.
     *
     * @param upperTrigger Y Bedrock max avant recalcul (ex: 270)
     * @param lowerTrigger Y Bedrock min avant recalcul (ex: -14)
     * @return true si un recalcul est nécessaire
     */
    public boolean needsOffsetUpdate(int upperTrigger, int lowerTrigger) {
        double bedrockY = toBedrockY(lastJavaY);
        return bedrockY > upperTrigger || bedrockY < lowerTrigger;
    }

    /**
     * Calcule l'offset optimal pour centrer le joueur dans la fenêtre Bedrock.
     * Résultat arrondi au multiple de 16 le plus proche.
     *
     * Formule : offset = javaY - BEDROCK_CENTER
     * BEDROCK_CENTER = -64 + 192 = 128
     *
     * Exemples :
     *   javaY=800 → raw=672 → aligned=672 → bedrockY=128 ✓
     *   javaY=64  → raw=-64 → aligned=-64 → bedrockY=128 ✓
     *   javaY=0   → raw=-128 → aligned=-128 → bedrockY=128 ✓
     */
    public static int computeOffset(double javaY) {
        final int BEDROCK_CENTER = 128; // -64 + 384/2
        final int BEDROCK_MIN = -64;
        final int BEDROCK_MAX = 320;

        int raw = (int) Math.round(javaY) - BEDROCK_CENTER;
        // Arrondir au multiple de 16 (alignement section)
        int aligned = (int) (Math.round((double) raw / 16.0) * 16);

        // Vérifier que bedrockY reste dans les limites
        int bedrockY = (int) Math.round(javaY) - aligned;
        if (bedrockY < BEDROCK_MIN) {
            // Réduire l'offset pour rester au-dessus du plancher
            aligned = (int) Math.round(javaY) - BEDROCK_MIN;
            aligned = (aligned / 16) * 16;
        } else if (bedrockY > BEDROCK_MAX) {
            // Augmenter l'offset pour rester sous le plafond
            aligned = (int) Math.round(javaY) - BEDROCK_MAX;
            aligned = (int) Math.ceil((double) aligned / 16.0) * 16;
        }

        return aligned;
    }

    @Override
    public String toString() {
        return String.format("PlayerOffsetData{name=%s, offset=%d, lastJavaY=%.1f, bedrockY=%.1f, changes=%d}",
            name, offset, lastJavaY, toBedrockY(lastJavaY), offsetChangeCount);
    }
}