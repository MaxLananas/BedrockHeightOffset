package fr.buildtheearth.bedrockheightoffset.geyser;

import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.logging.Logger;

public class GeyserHook {

    private static final Logger LOG = Logger.getLogger("BedrockHeightOffset");

    private static boolean floodgateAvailable = false;
    private static boolean geyserAvailable    = false;

    public static void initialize() {
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            FloodgateApi.getInstance();
            floodgateAvailable = true;
            LOG.info("[BHO] Floodgate API detected");
        } catch (Exception e) {
            LOG.warning("[BHO] Floodgate unavailable: " + e.getMessage());
        }

        try {
            Class.forName("org.geysermc.geyser.api.GeyserApi");
            geyserAvailable = true;
            LOG.info("[BHO] Geyser API detected");
        } catch (Exception e) {
            LOG.warning("[BHO] Geyser API unavailable");
        }
    }

    public static boolean isBedrockPlayer(Player player) {
        return isBedrockPlayer(player.getUniqueId(), player.getName());
    }

    public static boolean isBedrockPlayer(UUID uuid, String name) {
        if (floodgateAvailable) {
            try {
                return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
            } catch (Exception ignored) {}
        }
        if (uuid.toString().startsWith("00000000-0000-0000")) return true;
        return name != null && name.startsWith(".");
    }

    public static boolean isFloodgateAvailable() { return floodgateAvailable; }
    public static boolean isGeyserAvailable()    { return geyserAvailable;    }
}