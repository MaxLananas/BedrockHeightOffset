package fr.buildtheearth.bedrockheightoffset;

import fr.buildtheearth.bedrockheightoffset.command.BHOCommand;
import fr.buildtheearth.bedrockheightoffset.config.PluginConfig;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserHook;
import fr.buildtheearth.bedrockheightoffset.listener.PlayerConnectionListener;
import fr.buildtheearth.bedrockheightoffset.listener.PlayerMoveListener;
import fr.buildtheearth.bedrockheightoffset.protocol.BlockPacketAdapter;
import fr.buildtheearth.bedrockheightoffset.protocol.ChunkPacketAdapter;
import fr.buildtheearth.bedrockheightoffset.protocol.EntityPacketAdapter;
import fr.buildtheearth.bedrockheightoffset.protocol.PositionPacketAdapter;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class BedrockHeightOffset extends JavaPlugin {

    @Getter private static BedrockHeightOffset instance;
    @Getter private PluginConfig pluginConfig;
    @Getter private OffsetRegistry offsetRegistry;

    private ChunkPacketAdapter    chunkAdapter;
    private PositionPacketAdapter positionAdapter;
    private EntityPacketAdapter   entityAdapter;
    private BlockPacketAdapter    blockAdapter;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("╔══════════════════════════════════════════════╗");
        getLogger().info("║        BedrockHeightOffset v1.0.0            ║");
        getLogger().info("║    BuildTheEarth — terraplusminus support     ║");
        getLogger().info("╚══════════════════════════════════════════════╝");

        pluginConfig  = new PluginConfig(this);
        offsetRegistry = new OffsetRegistry();

        GeyserHook.initialize();

        if (!isProtocolLibAvailable()) {
            getLogger().severe("ProtocolLib not found or disabled — shutting down.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerListeners();
        registerProtocolAdapters();
        registerCommands();
        registerOnlinePlayers();

        getLogger().info(String.format(
            "[BHO] Ready | java=[%d,%d] bedrock=[-64,320] upper=%d lower=%d",
            pluginConfig.getJavaMinY(), pluginConfig.getJavaMaxY(),
            pluginConfig.getUpperTrigger(), pluginConfig.getLowerTrigger()
        ));
    }

    @Override
    public void onDisable() {
        if (chunkAdapter    != null) chunkAdapter.unregister();
        if (positionAdapter != null) positionAdapter.unregister();
        if (entityAdapter   != null) entityAdapter.unregister();
        if (blockAdapter    != null) blockAdapter.unregister();
        if (offsetRegistry  != null) offsetRegistry.clear();
        getLogger().info("BedrockHeightOffset disabled.");
        instance = null;
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerConnectionListener(this, offsetRegistry), this);
        pm.registerEvents(new PlayerMoveListener(this, offsetRegistry), this);
    }

    private void registerProtocolAdapters() {
        chunkAdapter    = new ChunkPacketAdapter(this, offsetRegistry);
        positionAdapter = new PositionPacketAdapter(this, offsetRegistry);
        entityAdapter   = new EntityPacketAdapter(this, offsetRegistry);
        blockAdapter    = new BlockPacketAdapter(this, offsetRegistry);

        chunkAdapter.register();
        positionAdapter.register();
        entityAdapter.register();
        blockAdapter.register();
    }

    private void registerCommands() {
        var cmd = getCommand("bho");
        if (cmd != null) {
            BHOCommand handler = new BHOCommand(this, offsetRegistry);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
    }

    private void registerOnlinePlayers() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean isBedrock = GeyserHook.isBedrockPlayer(player);
            if (pluginConfig.isBedrockOnly() && !isBedrock) continue;
            offsetRegistry.register(
                player.getUniqueId(), player.getName(),
                isBedrock, player.getLocation().getY()
            );
            count++;
        }
        if (count > 0) getLogger().info(count + " online player(s) registered on reload.");
    }

    private boolean isProtocolLibAvailable() {
        var plugin = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        return plugin != null && plugin.isEnabled();
    }

    public int getOffset(java.util.UUID uuid) {
        return offsetRegistry.getOffset(uuid);
    }

    public double toBedrockY(java.util.UUID uuid, double javaY) {
        return offsetRegistry.toBedrockY(uuid, javaY);
    }

    public double toJavaY(java.util.UUID uuid, double bedrockY) {
        return offsetRegistry.toJavaY(uuid, bedrockY);
    }
}