package fr.buildtheearth.bedrockheightoffset.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.ChunkCoordIntPair;
import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import org.bukkit.entity.Player;

/**
 * Intercepte les packets de chunks Java → Client.
 *
 * PacketType.Play.Server.MAP_CHUNK (Level Chunk With Light)
 *
 * Dans le contexte Paper + Geyser :
 * - Paper envoie des packets Java au client
 * - Geyser intercepte ces packets et les traduit en Bedrock
 * - ProtocolLib intercepte AVANT Geyser
 *
 * Ce qu'on peut faire ici :
 * - Logger les chunks envoyés pour debug
 * - Théoriquement modifier les données de chunk
 *
 * LIMITATION IMPORTANTE :
 * La modification des données de sub-chunks via ProtocolLib est complexe
 * car les données sont sérialisées en binaire (format NBT/Palette).
 * La vraie solution est dans Geyser (fork).
 *
 * Ce qu'on fait ici : on s'assure que l'offset est à jour avant
 * que le chunk soit envoyé, pour que Geyser le traduise correctement.
 */
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

        // Log pour debug
        plugin.getPluginConfig().debugLog(
            "Chunk envoyé à " + player.getName()
            + " (offset=" + data.getOffset() + ")"
            + " chunkX=" + event.getPacket().getIntegers().read(0)
            + " chunkZ=" + event.getPacket().getIntegers().read(1)
        );

        // Note: La modification des données de sub-chunks nécessite un fork Geyser.
        // ProtocolLib peut accéder aux données binaires du chunk via :
        // event.getPacket().getByteArrays().read(0) — mais le format est complexe.
        // L'implémentation complète est dans la Geyser extension.
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
    }
}