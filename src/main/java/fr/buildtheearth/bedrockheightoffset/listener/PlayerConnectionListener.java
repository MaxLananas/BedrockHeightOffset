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
        Player player    = event.getPlayer();
        boolean isBedrock = GeyserHook.isBedrockPlayer(player);

        if (plugin.getPluginConfig().isBedrockOnly() && !isBedrock) return;

        double javaY          = player.getLocation().getY();
        PlayerOffsetData data = registry.register(
            player.getUniqueId(), player.getName(), isBedrock, javaY
        );

        plugin.getLogger().info(String.format(
            "[BHO] %s %s registered | offset=%d | jY=%.1f | bY=%.1f",
            player.getName(), isBedrock ? "[BE]" : "[JE]",
            data.getOffset(), javaY, data.toBedrockY(javaY)
        ));

        if (isBedrock && GeyserSessionReflection.isReady()) {
            // Inject our Netty interceptor into Geyser's pipeline.
            // We delay 2 ticks to ensure Geyser has finished its own connection setup.
            UUID uuid = player.getUniqueId();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                injectInterceptor(uuid);
            }, 2L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        registry.unregister(uuid);
        plugin.getPluginConfig().debugLog(event.getPlayer().getName() + " unregistered");
    }

    private void injectInterceptor(UUID uuid) {
        try {
            Channel channel = GeyserSessionReflection.getBedrockChannel(uuid);
            if (channel == null) {
                plugin.getLogger().warning("[BHO] Cannot get Bedrock channel for " + uuid);
                return;
            }

            ChannelPipeline pipeline = channel.pipeline();

            // Avoid double-injection on reload
            if (pipeline.get(BedrockPacketInterceptor.HANDLER_NAME) != null) {
                pipeline.remove(BedrockPacketInterceptor.HANDLER_NAME);
            }

            // Insert BEFORE the first outbound handler (i.e., as close to the client as possible)
            // Geyser's pipeline typically looks like:
            //   ... encoder → raknet-codec → [we insert here] → ...
            // We insert after "packet-codec" which is where BedrockPackets live as objects.
            BedrockPacketInterceptor interceptor = new BedrockPacketInterceptor(uuid, registry, plugin);

            // Insert before the codec so we intercept decoded objects, not bytes
            if (pipeline.get("packet-codec") != null) {
                pipeline.addAfter("packet-codec", BedrockPacketInterceptor.HANDLER_NAME, interceptor);
            } else {
                // Fallback: insert as first handler
                pipeline.addFirst(BedrockPacketInterceptor.HANDLER_NAME, interceptor);
            }

            plugin.getLogger().info("[BHO] Netty interceptor injected for " + uuid);

        } catch (Exception e) {
            plugin.getLogger().severe("[BHO] Failed to inject interceptor for " + uuid + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}