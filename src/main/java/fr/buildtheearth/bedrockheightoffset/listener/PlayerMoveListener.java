package fr.buildtheearth.bedrockheightoffset.listener;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Surveille les mouvements des joueurs pour déclencher le recalcul d'offset
 * lorsqu'ils approchent des bords de la fenêtre Bedrock.
 */
public class PlayerMoveListener implements Listener {

    private final BedrockHeightOffset plugin;
    private final OffsetRegistry registry;

    public PlayerMoveListener(BedrockHeightOffset plugin, OffsetRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Optimisation : ne traiter que si Y a changé
        if (event.getFrom().getBlockY() == event.getTo().getBlockY()) {
            return;
        }

        Player player = event.getPlayer();
        PlayerOffsetData data = registry.get(player.getUniqueId());
        if (data == null) return;

        double newJavaY = event.getTo().getY();
        data.setLastJavaY(newJavaY);

        // Vérifier si un recalcul est nécessaire
        if (data.needsOffsetUpdate(
                plugin.getPluginConfig().getUpperTrigger(),
                plugin.getPluginConfig().getLowerTrigger())) {

            boolean changed = data.updateOffset(newJavaY);
            if (changed) {
                plugin.getLogger().info(
                    "[BHO] Offset recalculé pour " + player.getName()
                    + " : javaY=" + String.format("%.1f", newJavaY)
                    + " → offset=" + data.getOffset()
                    + " → bedrockY=" + String.format("%.1f", data.toBedrockY(newJavaY))
                );

                // Demander un resend de chunks après délai
                plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> triggerChunkResend(player, data),
                    plugin.getPluginConfig().getChunkResendDelayTicks()
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PlayerOffsetData data = registry.get(player.getUniqueId());
        if (data == null || event.getTo() == null) return;

        double newJavaY = event.getTo().getY();
        data.setLastJavaY(newJavaY);

        // Forcer le recalcul lors d'une téléportation (peut être n'importe quelle Y)
        boolean changed = data.updateOffset(newJavaY);
        if (changed) {
            plugin.getPluginConfig().debugLog(
                "Téléportation → offset recalculé pour " + player.getName()
                + " : " + data
            );
        }
    }

    /**
     * Force le rechargement des chunks autour du joueur côté Bedrock.
     * On envoie des chunks vides pour forcer le client à redemander les vrais.
     */
    private void triggerChunkResend(Player player, PlayerOffsetData data) {
        if (!player.isOnline()) return;

        plugin.getPluginConfig().debugLog(
            "Chunk resend pour " + player.getName()
            + " (offset=" + data.getOffset() + ")"
        );

        // Forcer le rechargement des chunks via Paper API
        // Paper permet de forcer le resend des chunks au joueur
        int viewDistance = player.getClientViewDistance();
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;

        // On rafraîchit uniquement les chunks proches (rayon 3) pour éviter la surcharge
        int radius = Math.min(3, viewDistance);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                player.getWorld().refreshChunk(cx + dx, cz + dz);
            }
        }
    }
}