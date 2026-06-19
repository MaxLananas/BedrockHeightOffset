package fr.buildtheearth.bedrockheightoffset.geyser;

import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.logging.Logger;

public class GeyserHook {

    private static final Logger LOGGER = Logger.getLogger("BedrockHeightOffset");

    private static boolean floodgateAvailable = false;
    private static boolean geyserAvailable    = false;

    public static void initialize() {
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            FloodgateApi.getInstance();
            floodgateAvailable = true;
            LOGGER.info("[BedrockHeightOffset] Floodgate API detected");
        } catch (Exception e) {
            LOGGER.warning("[BedrockHeightOffset] Floodgate unavailable: " + e.getMessage());
        }

        try {
            Class.forName("org.geysermc.geyser.api.GeyserApi");
            geyserAvailable = true;
            LOGGER.info("[BedrockHeightOffset] Geyser API detected");
        } catch (Exception e) {
            LOGGER.warning("[BedrockHeightOffset] Geyser API unavailable: " + e.getMessage());
        }
    }

    public static boolean isBedrockPlayer(Player player) {
        return isBedrockPlayer(player.getUniqueId(), player.getName());
    }

    public static boolean isBedrockPlayer(UUID uuid, String name) {
        if (floodgateAvailable) {
            try {
                return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
            } catch (Exception e) {
                LOGGER.fine("Floodgate isFloodgatePlayer failed: " + e.getMessage());
            }
        }
        if (uuid.toString().startsWith("00000000-0000-0000")) return true;
        return name != null && name.startsWith(".");
    }

    public static String getXuid(UUID uuid) {
        if (!floodgateAvailable) return null;
        try {
            var fp = FloodgateApi.getInstance().getPlayer(uuid);
            return fp != null ? fp.getXuid() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isFloodgateAvailable() { return floodgateAvailable; }
    public static boolean isGeyserAvailable()    { return geyserAvailable;    }
}