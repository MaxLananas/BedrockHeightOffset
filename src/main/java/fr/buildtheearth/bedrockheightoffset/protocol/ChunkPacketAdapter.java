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

public class ChunkPacketAdapter extends PacketAdapter {

    private final OffsetRegistry registry;
    private final BedrockHeightOffset plugin;

    public ChunkPacketAdapter(BedrockHeightOffset plugin, OffsetRegistry registry) {
        super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.MAP_CHUNK);
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

        plugin.getPluginConfig().debugLog(String.format(
            "MAP_CHUNK -> %s | chunkX=%d chunkZ=%d | offset=%d",
            player.getName(),
            event.getPacket().getIntegers().read(0),
            event.getPacket().getIntegers().read(1),
            data.getOffset()
        ));
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
    }
}