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

    @Getter
    private static BedrockHeightOffset instance;

    @Getter
    private PluginConfig pluginConfig;

    @Getter
    private OffsetRegistry offsetRegistry;

    // Protocol adapters
    private ChunkPacketAdapter chunkAdapter;
    private PositionPacketAdapter positionAdapter;
    private EntityPacketAdapter entityAdapter;
    private BlockPacketAdapter blockAdapter;

    @Override
    public void onEnable() {
        instance = this;

        printBanner();

        // 1. Configuration
        pluginConfig = new PluginConfig(this);

        // 2. Registre d'offsets
        offsetRegistry = new OffsetRegistry();

        // 3. Hooks Geyser/Floodgate
        GeyserHook.initialize();

        // 4. Vérification ProtocolLib
        if (!isProtocolLibAvailable()) {
            getLogger().severe("╔═══════════════════════════════════════╗");
            getLogger().severe("║ ERREUR : ProtocolLib non disponible ! ║");
            getLogger().severe("║ Le plugin sera désactivé.             ║");
            getLogger().severe("╚═══════════════════════════════════════╝");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 5. Enregistrer les listeners Bukkit
        registerListeners();

        // 6. Enregistrer les adapters ProtocolLib
        registerProtocolAdapters();

        // 7. Commandes
        registerCommands();

        // 8. Enregistrer les joueurs déjà connectés (reload à chaud)
        registerOnlinePlayers();

        // 9. Log de démarrage
        logStartupInfo();

        getLogger().info("BedrockHeightOffset activé avec succès !");
    }

    @Override
    public void onDisable() {
        // Désinscrire les adapters ProtocolLib
        if (chunkAdapter != null)    chunkAdapter.unregister();
        if (positionAdapter != null) positionAdapter.unregister();
        if (entityAdapter != null)   entityAdapter.unregister();
        if (blockAdapter != null)    blockAdapter.unregister();

        // Nettoyer le registre
        if (offsetRegistry != null) offsetRegistry.clear();

        getLogger().info("BedrockHeightOffset désactivé.");
        instance = null;
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerConnectionListener(this, offsetRegistry), this);
        pm.registerEvents(new PlayerMoveListener(this, offsetRegistry), this);
        getLogger().info("Listeners Bukkit enregistrés.");
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

        getLogger().info("Adapters ProtocolLib enregistrés.");
    }

    private void registerCommands() {
        BHOCommand cmd = new BHOCommand(this, offsetRegistry);
        var command = getCommand("bho");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
            getLogger().info("Commande /bho enregistrée.");
        }
    }

    /**
     * Enregistre les joueurs déjà en ligne (cas d'un /reload ou redémarrage à chaud).
     */
    private void registerOnlinePlayers() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean isBedrock = GeyserHook.isBedrockPlayer(player);
            if (pluginConfig.isBedrockOnly() && !isBedrock) continue;

            offsetRegistry.register(
                player.getUniqueId(),
                player.getName(),
                isBedrock,
                player.getLocation().getY()
            );
            count++;
        }
        if (count > 0) {
            getLogger().info(count + " joueur(s) déjà connecté(s) enregistré(s).");
        }
    }

    private boolean isProtocolLibAvailable() {
        try {
            Class.forName("com.comphenix.protocol.ProtocolLibrary");
            var plugin = Bukkit.getPluginManager().getPlugin("ProtocolLib");
            return plugin != null && plugin.isEnabled();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void printBanner() {
        getLogger().info("╔══════════════════════════════════════════════╗");
        getLogger().info("║        BedrockHeightOffset v1.0.0            ║");
        getLogger().info("║    BuildTheEarth — terraplusminus support     ║");
        getLogger().info("║    Paper 1.21.10 + ProtocolLib + Geyser       ║");
        getLogger().info("╚══════════════════════════════════════════════╝");
    }

    private void logStartupInfo() {
        getLogger().info("── Configuration ──────────────────────────────");
        getLogger().info("  Java world    : Y=" + pluginConfig.getJavaMinY()
            + " → Y=" + pluginConfig.getJavaMaxY());
        getLogger().info("  Bedrock window: Y=" + pluginConfig.getBedrockMinY()
            + " → Y=" + pluginConfig.getBedrockMaxY()
            + " (height=" + pluginConfig.getBedrockHeight() + ")");
        getLogger().info("  Center        : Y=" + pluginConfig.getBedrockCenter());
        getLogger().info("  Trigger haut  : Y=" + pluginConfig.getUpperTrigger());
        getLogger().info("  Trigger bas   : Y=" + pluginConfig.getLowerTrigger());
        getLogger().info("  Bedrock only  : " + pluginConfig.isBedrockOnly());
        getLogger().info("  Debug         : " + pluginConfig.isDebug());
        getLogger().info("  Floodgate     : " + GeyserHook.isFloodgateAvailable());
        getLogger().info("  Geyser        : " + GeyserHook.isGeyserAvailable());
        getLogger().info("───────────────────────────────────────────────");
    }

    /**
     * Retourne l'offset actuel d'un joueur (0 si non enregistré).
     */
    public int getOffset(java.util.UUID uuid) {
        return offsetRegistry.getOffset(uuid);
    }

    /**
     * Convertit une coordonnée Y Java → Bedrock pour un joueur.
     */
    public double toBedrockY(java.util.UUID uuid, double javaY) {
        return offsetRegistry.toBedrockY(uuid, javaY);
    }

    /**
     * Convertit une coordonnée Y Bedrock → Java pour un joueur.
     */
    public double toJavaY(java.util.UUID uuid, double bedrockY) {
        return offsetRegistry.toJavaY(uuid, bedrockY);
    }
}