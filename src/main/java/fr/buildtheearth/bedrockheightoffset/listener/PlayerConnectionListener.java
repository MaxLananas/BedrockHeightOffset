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

        if (isBedrock && GeyserSessionReflection.isReady()) {
            UUID uuid = player.getUniqueId();

            // Step 1 (tick 3): patch the session's BedrockDimension so Geyser
            // stops dropping sections above Y=320 before they reach our interceptor.
            // Must run after Geyser's connect() which fires on the same tick as join.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                GeyserSessionReflection.patchSessionDimension(uuid);
            }, 3L);

            // Step 2 (tick 5+): inject our Netty interceptor.
            scheduleInjection(uuid, 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        registry.unregister(uuid);
        plugin.getPluginConfig().debugLog(event.getPlayer().getName() + " unregistered");
    }

    // ── Netty injection with retry ────────────────────────────────────────────

    private void scheduleInjection(UUID uuid, int attempt) {
        if (attempt >= 8) {
            plugin.getLogger().warning("[BHO] Gave up injecting interceptor after 8 attempts for " + uuid);
            return;
        }

        long delay = 5L + attempt * 3L; // ticks: 5, 8, 11, 14 ...
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            boolean ok = tryInject(uuid);
            if (!ok) {
                plugin.getPluginConfig().debugLog(
                    "Injection attempt " + (attempt + 1) + " failed for " + uuid + ", retry in "
                    + (5L + (attempt + 1) * 3L) + " ticks"
                );
                scheduleInjection(uuid, attempt + 1);
            }
        }, delay);
    }

    private boolean tryInject(UUID uuid) {
        try {
            Channel channel = GeyserSessionReflection.getBedrockChannel(uuid);
            if (channel == null) return false;

            ChannelPipeline pipeline = channel.pipeline();

            if (pipeline.get(BedrockPacketInterceptor.HANDLER_NAME) != null) {
                pipeline.remove(BedrockPacketInterceptor.HANDLER_NAME);
            }

            BedrockPacketInterceptor interceptor =
                new BedrockPacketInterceptor(uuid, registry, plugin);

            // Find the best insertion point in the pipeline.
            // We need to be AFTER the codec that decodes raw bytes into BedrockPacket objects,
            // and BEFORE the handler that processes them (Geyser's packet handler).
            List<String> names = pipeline.names();
            plugin.getPluginConfig().debugLog("Pipeline handlers: " + names);

            String insertAfter = findCodecHandler(names);
            if (insertAfter != null) {
                pipeline.addAfter(insertAfter, BedrockPacketInterceptor.HANDLER_NAME, interceptor);
            } else {
                pipeline.addFirst(BedrockPacketInterceptor.HANDLER_NAME, interceptor);
            }

            plugin.getLogger().info("[BHO] Netty interceptor injected for " + uuid
                + " after '" + (insertAfter != null ? insertAfter : "first") + "'");
            return true;

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("tryInject error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Finds the codec handler name where BedrockPackets exist as objects
     * (not yet encoded to bytes). We insert our handler right after this.
     */
    private String findCodecHandler(List<String> names) {
        // Priority order: these are the known handler names used by CloudburstMC protocol lib
        String[] candidates = {
            "packet-codec",
            "bedrock-packet-codec",
            "codec",
            "frame-codec"
        };
        for (String candidate : candidates) {
            if (names.contains(candidate)) return candidate;
        }
        return null;
    }
}