package fr.buildtheearth.bedrockheightoffset.listener;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Gère l'enregistrement/désenregistrement des joueurs dans le registre d'offsets.
 */
public class PlayerConnectionListener implements Listener {

    private final BedrockHeightOffset plugin;
    private final OffsetRegistry registry;

    public PlayerConnectionListener(BedrockHeightOffset plugin, OffsetRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Déterminer si joueur Bedrock
        boolean isBedrock = GeyserHook.isBedrockPlayer(player);

        // Si bedrock-only est activé et que ce n'est pas un joueur Bedrock, ignorer
        if (plugin.getPluginConfig().isBedrockOnly() && !isBedrock) {
            return;
        }

        // Récupérer la position Y actuelle
        double javaY = player.getLocation().getY();

        // Enregistrer dans le registre
        PlayerOffsetData data = registry.register(
            player.getUniqueId(),
            player.getName(),
            isBedrock,
            javaY
        );

        plugin.getPluginConfig().debugLog(
            "Joueur enregistré : " + data
        );

        plugin.getLogger().info(
            "[BHO] " + player.getName()
            + (isBedrock ? " [BEDROCK]" : " [JAVA]")
            + " → offset=" + data.getOffset()
            + ", javaY=" + String.format("%.1f", javaY)
            + ", bedrockY=" + String.format("%.1f", data.toBedrockY(javaY))
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (registry.isRegistered(player.getUniqueId())) {
            registry.unregister(player.getUniqueId());
            plugin.getPluginConfig().debugLog("Joueur désenregistré : " + player.getName());
        }
    }
}