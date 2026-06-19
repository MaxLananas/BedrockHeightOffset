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

import java.util.UUID;

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
        boolean isBedrock = GeyserHook.isBedrockPlayer(player);

        if (plugin.getPluginConfig().isBedrockOnly() && !isBedrock) return;

        double javaY = player.getLocation().getY();
        PlayerOffsetData data = registry.register(
            player.getUniqueId(), player.getName(), isBedrock, javaY
        );

        plugin.getLogger().info(String.format(
            "[BHO] %s %s registered | offset=%d | javaY=%.1f | bedrockY=%.1f",
            player.getName(),
            isBedrock ? "[BEDROCK]" : "[JAVA]",
            data.getOffset(), javaY, data.toBedrockY(javaY)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (registry.isRegistered(uuid)) {
            registry.unregister(uuid);
            plugin.getPluginConfig().debugLog(event.getPlayer().getName() + " unregistered");
        }
    }
}