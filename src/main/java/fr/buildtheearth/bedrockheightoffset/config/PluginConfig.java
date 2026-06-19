package fr.buildtheearth.bedrockheightoffset.config;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

@Getter
public class PluginConfig {

    private final BedrockHeightOffset plugin;

    private int     javaMinY, javaMaxY;
    private int     bedrockMinY, bedrockMaxY, bedrockHeight, bedrockCenter;
    private int     upperTrigger, lowerTrigger;
    private int     chunkResendDelayTicks;
    private boolean debug, bedrockOnly;

    public PluginConfig(BedrockHeightOffset plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        javaMinY              = c.getInt("java-world.min-y",         -64);
        javaMaxY              = c.getInt("java-world.max-y",        2032);
        bedrockMinY           = c.getInt("bedrock-window.min-y",     -64);
        bedrockMaxY           = c.getInt("bedrock-window.max-y",     320);
        bedrockHeight         = c.getInt("bedrock-window.height",    384);
        bedrockCenter         = c.getInt("bedrock-window.center",    128);
        upperTrigger          = c.getInt("triggers.upper",           270);
        lowerTrigger          = c.getInt("triggers.lower",           -14);
        chunkResendDelayTicks = c.getInt("chunk-resend-delay-ticks",  10);
        debug                 = c.getBoolean("debug",               false);
        bedrockOnly           = c.getBoolean("bedrock-only",         true);
    }

    public void debugLog(String msg) {
        if (debug) plugin.getLogger().info("[DEBUG] " + msg);
    }
}