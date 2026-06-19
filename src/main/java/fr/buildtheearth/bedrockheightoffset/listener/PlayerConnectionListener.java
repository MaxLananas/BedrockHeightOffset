package fr.buildtheearth.bedrockheightoffset.listener;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserHook;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserSessionReflection;
import fr.buildtheearth.bedrockheightoffset.netty.BedrockPacketInterceptor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

public class PlayerConnectionListener implements Listener {

    private final BedrockHeightOffset plugin;
    private final OffsetRegistry      registry;

    public PlayerConnectionListener(BedrockHeightOffset plugin, OffsetRegistry registry) {
        this.plugin   = plugin;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player  player    = event.getPlayer();
        boolean isBedrock = GeyserHook.isBedrockPlayer(player);

        if (plugin.getPluginConfig().isBedrockOnly() && !isBedrock) return;

        double javaY = player.getLocation().getY();
        PlayerOffsetData data = registry.register(
            player.getUniqueId(), player.getName(), isBedrock, javaY
        );

        plugin.getLogger().info(String.format(
            "[BHO] %s %s registered | offset=%d (%d sec) | jY=%.1f | bY=%.1f",
            player.getName(), isBedrock ? "[BE]" : "[JE]",
            data.getOffset(), data.offsetSections(),
            javaY, data.toBedrockY(javaY)
        ));

        if (!isBedrock || !GeyserSessionReflection.isReady()) return;

        UUID uuid = player.getUniqueId();

        // Tick 1 — attempt dimension patch immediately.
        // Geyser's connect() fires synchronously during login, so by the
        // time PlayerJoinEvent fires the BedrockDimension object exists.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            GeyserSessionReflection.patchSessionDimension(uuid);
        });

        // Tick 1 again, then retry — also patch again at tick 4 in case
        // Geyser recreates the dimension object during the spawn sequence.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            GeyserSessionReflection.patchSessionDimension(uuid);
        }, 4L);

        // Netty interceptor injection with retry
        scheduleInjection(uuid, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        registry.unregister(event.getPlayer().getUniqueId());
    }

    private void scheduleInjection(UUID uuid, int attempt) {
        if (attempt >= 8) {
            plugin.getLogger().warning("[BHO] Gave up injecting for " + uuid + " after 8 attempts");
            return;
        }
        long delay = 5L + attempt * 3L;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!tryInject(uuid)) {
                plugin.getPluginConfig().debugLog(
                    "Injection attempt " + (attempt + 1) + " failed for " + uuid);
                scheduleInjection(uuid, attempt + 1);
            }
        }, delay);
    }

    private boolean tryInject(UUID uuid) {
        try {
            Channel channel = GeyserSessionReflection.getBedrockChannel(uuid);
            if (channel == null) return false;

            ChannelPipeline pipeline = channel.pipeline();
            List<String>    names    = pipeline.names();

            plugin.getPluginConfig().debugLog("Pipeline for " + uuid + ": " + names);

            if (pipeline.get(BedrockPacketInterceptor.HANDLER_NAME) != null) {
                pipeline.remove(BedrockPacketInterceptor.HANDLER_NAME);
            }

            BedrockPacketInterceptor interceptor =
                new BedrockPacketInterceptor(uuid, registry, plugin);

            String anchor = findInsertionPoint(names);
            if (anchor != null) {
                pipeline.addAfter(anchor, BedrockPacketInterceptor.HANDLER_NAME, interceptor);
            } else {
                pipeline.addFirst(BedrockPacketInterceptor.HANDLER_NAME, interceptor);
            }

            plugin.getLogger().info("[BHO] Interceptor injected for " + uuid
                + " | after=" + (anchor != null ? anchor : "first")
                + " | pipeline=" + pipeline.names());
            return true;

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("tryInject: " + e.getMessage());
            return false;
        }
    }

    private String findInsertionPoint(List<String> names) {
        for (String candidate : List.of(
                "packet-codec",
                "bedrock-packet-codec",
                "codec",
                "frame-codec")) {
            if (names.contains(candidate)) return candidate;
        }
        return null;
    }
}