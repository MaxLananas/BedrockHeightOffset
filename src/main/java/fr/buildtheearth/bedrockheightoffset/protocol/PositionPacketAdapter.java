package fr.buildtheearth.bedrockheightoffset.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import org.bukkit.entity.Player;

public class PositionPacketAdapter extends PacketAdapter {

    private final OffsetRegistry registry;
    private final BedrockHeightOffset plugin;

    public PositionPacketAdapter(BedrockHeightOffset plugin, OffsetRegistry registry) {
        super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.POSITION);
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        if (player == null) return;

        PlayerOffsetData data = registry.get(player.getUniqueId());
        if (data == null) return;

        try {
            // POSITION packet doubles layout: x(0), y(1), z(2), deltaX(3), deltaY(4), deltaZ(5)
            double javaY = event.getPacket().getDoubles().read(1);

            data.setLastJavaY(javaY);
            boolean changed = data.updateOffset(javaY);

            if (changed) {
                plugin.getLogger().info(String.format(
                    "[BHO] POSITION packet -> offset recalculated for %s | javaY=%.2f | offset=%d",
                    player.getName(), javaY, data.getOffset()
                ));
            }

            plugin.getPluginConfig().debugLog(String.format(
                "POSITION -> %s | javaY=%.2f | offset=%d | bedrockY=%.2f",
                player.getName(), javaY, data.getOffset(), data.toBedrockY(javaY)
            ));

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog("POSITION packet read error: " + e.getMessage());
        }
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
    }
}