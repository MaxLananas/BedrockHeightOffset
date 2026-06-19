package fr.buildtheearth.bedrockheightoffset.geyser;

import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Couche d'abstraction pour détecter les joueurs Bedrock.
 *
 * Priorité de détection :
 * 1. Floodgate API (méthode la plus fiable)
 * 2. Préfixe UUID Floodgate (fallback)
 * 3. Préfixe username "." (fallback Floodgate)
 */
public class GeyserHook {

    private static final Logger LOGGER = Logger.getLogger("BedrockHeightOffset");

    private static boolean floodgateAvailable = false;
    private static boolean geyserAvailable = false;

    /**
     * Initialise les hooks. Appeler une fois au démarrage.
     */
    public static void initialize() {
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            FloodgateApi.getInstance(); // vérifie que l'instance est disponible
            floodgateAvailable = true;
            LOGGER.info("[BedrockHeightOffset] Floodgate API détectée ✓");
        } catch (ClassNotFoundException | Exception e) {
            LOGGER.warning("[BedrockHeightOffset] Floodgate non disponible : " + e.getMessage());
        }

        try {
            Class.forName("org.geysermc.geyser.api.GeyserApi");
            geyserAvailable = true;
            LOGGER.info("[BedrockHeightOffset] Geyser API détectée ✓");
        } catch (ClassNotFoundException e) {
            LOGGER.warning("[BedrockHeightOffset] Geyser API non disponible.");
        }
    }

    /**
     * Retourne true si le joueur est un joueur Bedrock connecté via Geyser/Floodgate.
     */
    public static boolean isBedrockPlayer(Player player) {
        return isBedrockPlayer(player.getUniqueId(), player.getName());
    }

    /**
     * Retourne true si l'UUID/nom correspond à un joueur Bedrock.
     */
    public static boolean isBedrockPlayer(UUID uuid, String name) {
        // 1. Méthode Floodgate (la plus fiable)
        if (floodgateAvailable) {
            try {
                return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
            } catch (Exception e) {
                LOGGER.fine("Floodgate check failed: " + e.getMessage());
            }
        }

        // 2. Fallback : les UUID Floodgate commencent par 00000000-0000-0000
        String uuidStr = uuid.toString();
        if (uuidStr.startsWith("00000000-0000-0000")) {
            return true;
        }

        // 3. Fallback : préfixe "." (configuration Floodgate par défaut)
        if (name != null && name.startsWith(".")) {
            return true;
        }

        return false;
    }

    /**
     * Retourne le XUID Bedrock si disponible (Floodgate).
     */
    public static String getXuid(UUID uuid) {
        if (floodgateAvailable) {
            try {
                var fp = FloodgateApi.getInstance().getPlayer(uuid);
                return fp != null ? fp.getXuid() : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static boolean isFloodgateAvailable() { return floodgateAvailable; }
    public static boolean isGeyserAvailable()    { return geyserAvailable;    }
}