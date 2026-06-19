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

/**
 * Intercepte les packets de mise à jour de blocs (Java → Client).
 * Permet de logger/tracker les updates de blocs avec l'offset actuel.
 */
public class BlockPacketAdapter extends PacketAdapter {

    private final OffsetRegistry registry;
    private final BedrockHeightOffset plugin;

    public BlockPacketAdapter(BedrockHeightOffset plugin, OffsetRegistry registry) {
        super(plugin, ListenerPriority.NORMAL,
            PacketType.Play.Server.BLOCK_CHANGE,
            PacketType.Play.Server.MULTI_BLOCK_CHANGE);
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        if (event.isCancelled()) return;
        if (!plugin.getPluginConfig().isDebug()) return;

        Player player = event.getPlayer();
        if (player == null) return;

        PlayerOffsetData data = registry.get(player.getUniqueId());
        if (data == null) return;

        plugin.getPluginConfig().debugLog(
            "BLOCK_UPDATE packet → " + player.getName()
            + " (offset=" + data.getOffset() + ")"
        );
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
    }
}