package fr.buildtheearth.bedrockheightoffset.config;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Wrapper de configuration type-safe.
 */
@Getter
public class PluginConfig {

    private final BedrockHeightOffset plugin;

    // Java world
    private int javaMinY;
    private int javaMaxY;

    // Bedrock window (fixed)
    private int bedrockMinY;
    private int bedrockMaxY;
    private int bedrockHeight;
    private int bedrockCenter;

    // Triggers
    private int upperTrigger;
    private int lowerTrigger;

    // Misc
    private int chunkResendDelayTicks;
    private boolean debug;
    private boolean bedrockOnly;

    public PluginConfig(BedrockHeightOffset plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        javaMinY              = cfg.getInt("java-world.min-y", -64);
        javaMaxY              = cfg.getInt("java-world.max-y", 2032);

        bedrockMinY           = cfg.getInt("bedrock-window.min-y", -64);
        bedrockMaxY           = cfg.getInt("bedrock-window.max-y", 320);
        bedrockHeight         = cfg.getInt("bedrock-window.height", 384);
        bedrockCenter         = cfg.getInt("bedrock-window.center", 128);

        upperTrigger          = cfg.getInt("triggers.upper", 270);
        lowerTrigger          = cfg.getInt("triggers.lower", -14);

        chunkResendDelayTicks = cfg.getInt("chunk-resend-delay-ticks", 10);
        debug                 = cfg.getBoolean("debug", false);
        bedrockOnly           = cfg.getBoolean("bedrock-only", true);
    }

    public void debugLog(String msg) {
        if (debug) {
            plugin.getLogger().info("[DEBUG] " + msg);
        }
    }
}