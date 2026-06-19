package fr.buildtheearth.bedrockheightoffset;

import fr.buildtheearth.bedrockheightoffset.command.BHOCommand;
import fr.buildtheearth.bedrockheightoffset.config.PluginConfig;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserHook;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserSessionReflection;
import fr.buildtheearth.bedrockheightoffset.listener.PlayerConnectionListener;
import fr.buildtheearth.bedrockheightoffset.listener.PlayerMoveListener;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class BedrockHeightOffset extends JavaPlugin {

    @Getter private static BedrockHeightOffset instance;
    @Getter private PluginConfig   pluginConfig;
    @Getter private OffsetRegistry offsetRegistry;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("╔══════════════════════════════════════════════════╗");
        getLogger().info("║     BedrockHeightOffset v2.0.0                   ║");
        getLogger().info("║     Netty-level Bedrock packet interception       ║");
        getLogger().info("║     BuildTheEarth — terraplusminus Y=-64→1952    ║");
        getLogger().info("╚══════════════════════════════════════════════════╝");

        pluginConfig   = new PluginConfig(this);
        offsetRegistry = new OffsetRegistry();

        GeyserHook.initialize();
        GeyserSessionReflection.initialize();

        if (!GeyserSessionReflection.isReady()) {
            getLogger().severe("[BHO] Geyser reflection failed — check your Geyser version.");
        }
        if (!GeyserSessionReflection.isDimensionPatched()) {
            getLogger().severe("[BHO] BedrockDimension patch failed — chunks above Y=320 will be invisible!");
        }

        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerConnectionListener(this, offsetRegistry), this);
        pm.registerEvents(new PlayerMoveListener(this, offsetRegistry), this);

        var cmd = getCommand("bho");
        if (cmd != null) {
            BHOCommand h = new BHOCommand(this, offsetRegistry);
            cmd.setExecutor(h);
            cmd.setTabCompleter(h);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean isBedrock = GeyserHook.isBedrockPlayer(p);
            if (pluginConfig.isBedrockOnly() && !isBedrock) continue;
            offsetRegistry.register(
                p.getUniqueId(), p.getName(), isBedrock, p.getLocation().getY()
            );
        }

        getLogger().info(String.format(
            "[BHO] Active | java=[%d,%d] | bedrock=[-64,320] | triggers=[%d,%d] | dimension_patched=%b",
            pluginConfig.getJavaMinY(), pluginConfig.getJavaMaxY(),
            pluginConfig.getUpperTrigger(), pluginConfig.getLowerTrigger(),
            GeyserSessionReflection.isDimensionPatched()
        ));
    }

    @Override
    public void onDisable() {
        if (offsetRegistry != null) offsetRegistry.clear();
        getLogger().info("[BHO] Disabled.");
        instance = null;
    }

    public int    getOffset(java.util.UUID uuid)              { return offsetRegistry.getOffset(uuid); }
    public double toBedrockY(java.util.UUID uuid, double jY)  { return offsetRegistry.toBedrockY(uuid, jY); }
    public double toJavaY(java.util.UUID uuid, double bY)     { return offsetRegistry.toJavaY(uuid, bY); }
}