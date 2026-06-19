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

public class PlayerMoveListener implements Listener {

    private final BedrockHeightOffset plugin;
    private final OffsetRegistry      registry;

    public PlayerMoveListener(BedrockHeightOffset plugin, OffsetRegistry registry) {
        this.plugin   = plugin;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockY() == event.getTo().getBlockY()) return;

        Player           player = event.getPlayer();
        PlayerOffsetData data   = registry.get(player.getUniqueId());
        if (data == null) return;

        double newJavaY = event.getTo().getY();
        data.setLastJavaY(newJavaY);

        if (data.needsOffsetUpdate(
                plugin.getPluginConfig().getUpperTrigger(),
                plugin.getPluginConfig().getLowerTrigger())) {

            boolean changed = data.updateOffset(newJavaY);
            if (changed) {
                plugin.getLogger().info(String.format(
                    "[BHO] Offset recalc %s | jY=%.1f | off=%d | bY=%.1f",
                    player.getName(), newJavaY, data.getOffset(), data.toBedrockY(newJavaY)
                ));
                scheduleChunkResend(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;

        Player           player  = event.getPlayer();
        PlayerOffsetData data    = registry.get(player.getUniqueId());
        if (data == null) return;

        double newJavaY = event.getTo().getY();
        data.setLastJavaY(newJavaY);

        boolean changed = data.updateOffset(newJavaY);
        if (changed) {
            plugin.getPluginConfig().debugLog("Teleport offset recalc: " + data);
            scheduleChunkResend(player);
        }
    }

    private void scheduleChunkResend(Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            int radius = Math.min(3, player.getClientViewDistance());
            int cx = player.getLocation().getBlockX() >> 4;
            int cz = player.getLocation().getBlockZ() >> 4;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    player.getWorld().refreshChunk(cx + dx, cz + dz);
                }
            }
        }, plugin.getPluginConfig().getChunkResendDelayTicks());
    }
}