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
            data.getOffset(), data.offsetSections(), javaY, data.toBedrockY(javaY)
        ));

        if (!isBedrock) return;

        UUID uuid = player.getUniqueId();

        // Tick 0: if reflection failed statically, try live discovery
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!GeyserSessionReflection.isReady()) {
                plugin.getLogger().info("[BHO] Attempting live reflection discovery for " + uuid);
                GeyserSessionReflection.initFromLiveConnection(uuid);
            }
            GeyserSessionReflection.patchSessionDimension(uuid);
        });

        // Tick 4: repatch (Geyser may rebuild dimension during spawn)
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            GeyserSessionReflection.patchSessionDimension(uuid), 4L);

        // Tick 20: repatch again after full spawn sequence
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            GeyserSessionReflection.patchSessionDimension(uuid), 20L);

        // Netty injection
        scheduleInjection(uuid, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        registry.unregister(event.getPlayer().getUniqueId());
    }

    private void scheduleInjection(UUID uuid, int attempt) {
        if (attempt >= 10) {
            plugin.getLogger().warning("[BHO] Gave up injecting for " + uuid);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!tryInject(uuid)) scheduleInjection(uuid, attempt + 1);
        }, 5L + attempt * 3L);
    }

    private boolean tryInject(UUID uuid) {
        try {
            Channel ch = GeyserSessionReflection.getBedrockChannel(uuid);
            if (ch == null) return false;

            ChannelPipeline pl = ch.pipeline();
            List<String>   names = pl.names();

            plugin.getPluginConfig().debugLog("Pipeline " + uuid + ": " + names);

            if (pl.get(BedrockPacketInterceptor.HANDLER_NAME) != null)
                pl.remove(BedrockPacketInterceptor.HANDLER_NAME);

            BedrockPacketInterceptor interceptor =
                new BedrockPacketInterceptor(uuid, registry, plugin);

            String anchor = null;
            for (String c : List.of("packet-codec","bedrock-packet-codec","codec","frame-codec")) {
                if (names.contains(c)) { anchor = c; break; }
            }

            if (anchor != null) pl.addAfter(anchor, BedrockPacketInterceptor.HANDLER_NAME, interceptor);
            else                pl.addFirst(BedrockPacketInterceptor.HANDLER_NAME, interceptor);

            plugin.getLogger().info("[BHO] Interceptor injected for " + uuid
                + " after=" + anchor + " | pipeline=" + pl.names());
            return true;

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("tryInject: " + e.getMessage());
            return false;
        }
    }
}