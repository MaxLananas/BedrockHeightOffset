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

        if (isBedrock && GeyserSessionReflection.isReady()) {
            UUID uuid = player.getUniqueId();
            // Retry injection up to 5 times with 2-tick intervals
            // to handle cases where the Geyser channel isn't ready yet
            scheduleInjectionWithRetry(uuid, 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        registry.unregister(uuid);
        plugin.getPluginConfig().debugLog(event.getPlayer().getName() + " unregistered");
    }

    private void scheduleInjectionWithRetry(UUID uuid, int attempt) {
        if (attempt >= 5) {
            plugin.getLogger().warning("[BHO] Failed to inject interceptor after 5 attempts for " + uuid);
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            boolean success = injectInterceptor(uuid);
            if (!success) {
                plugin.getPluginConfig().debugLog(
                    "Injection attempt " + (attempt + 1) + " failed for " + uuid + ", retrying..."
                );
                scheduleInjectionWithRetry(uuid, attempt + 1);
            }
        }, 2L + attempt * 2L);
    }

    private boolean injectInterceptor(UUID uuid) {
        try {
            Channel channel = GeyserSessionReflection.getBedrockChannel(uuid);
            if (channel == null) return false;

            ChannelPipeline pipeline = channel.pipeline();

            // Remove existing handler to avoid duplicates (e.g. on hot reload)
            if (pipeline.get(BedrockPacketInterceptor.HANDLER_NAME) != null) {
                pipeline.remove(BedrockPacketInterceptor.HANDLER_NAME);
            }

            BedrockPacketInterceptor interceptor =
                new BedrockPacketInterceptor(uuid, registry, plugin);

            // Insert after packet-codec so we see deserialized BedrockPacket objects,
            // not raw bytes. "packet-codec" is the handler name used by CloudburstMC protocol.
            if (pipeline.get("packet-codec") != null) {
                pipeline.addAfter("packet-codec", BedrockPacketInterceptor.HANDLER_NAME, interceptor);
            } else if (pipeline.get("bedrock-packet-codec") != null) {
                pipeline.addAfter("bedrock-packet-codec", BedrockPacketInterceptor.HANDLER_NAME, interceptor);
            } else {
                // Last resort: insert as first handler
                pipeline.addFirst(BedrockPacketInterceptor.HANDLER_NAME, interceptor);
                plugin.getLogger().warning(
                    "[BHO] packet-codec not found in pipeline for " + uuid
                    + " — inserted as first handler. Pipeline: " + pipeline.names()
                );
            }

            plugin.getLogger().info("[BHO] Netty interceptor injected for " + uuid
                + " (pipeline position: after " + getPreviousHandlerName(pipeline) + ")");
            return true;

        } catch (Exception e) {
            plugin.getLogger().warning("[BHO] Injection failed for " + uuid + ": " + e.getMessage());
            plugin.getPluginConfig().debugLog("Injection stack: " + e);
            return false;
        }
    }

    private String getPreviousHandlerName(ChannelPipeline pipeline) {
        String name = BedrockPacketInterceptor.HANDLER_NAME;
        java.util.List<String> names = pipeline.names();
        int idx = names.indexOf(name);
        return idx > 0 ? names.get(idx - 1) : "unknown";
    }
}