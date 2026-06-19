package fr.buildtheearth.bedrockheightoffset.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import org.bukkit.entity.Player;

/**
 * Intercepte les packets de position joueur (Java → Client).
 *
 * Packets interceptés :
 * - POSITION (ClientboundPlayerPositionPacket) : téléportation/spawn
 *
 * Direction : Server → Client
 * On modifie la coordonnée Y avant que Geyser ne le reçoive,
 * mais Geyser utilise sa propre valeur Y depuis GeyserSession.
 *
 * IMPORTANT : Ce packet est intercepté côté Java.
 * Geyser reçoit le packet Java original et fait sa propre traduction.
 * Pour modifier ce que Geyser envoie au client Bedrock, il faut
 * soit un fork Geyser, soit la Geyser Extension.
 *
 * Ce qu'on fait ici : mettre à jour l'offset quand le serveur
 * téléporte le joueur, pour que notre registre soit cohérent.
 */
public class PositionPacketAdapter extends PacketAdapter {

    private final OffsetRegistry registry;
    private final BedrockHeightOffset plugin;

    public PositionPacketAdapter(BedrockHeightOffset plugin, OffsetRegistry registry) {
        super(plugin, ListenerPriority.HIGHEST,
            PacketType.Play.Server.POSITION);
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
            // Lire la position Y du packet
            // ClientboundPlayerPositionPacket contient :
            // double x, y, z, deltaX, deltaY, deltaZ, float xRot, yRot, int id
            // Via ProtocolLib on accède aux doubles
            StructureModifier<Double> doubles = event.getPacket().getDoubles();

            // Y est le second double (index 1) dans le packet de position
            double javaY = doubles.read(1);

            // Mettre à jour l'offset si nécessaire
            data.setLastJavaY(javaY);
            boolean changed = data.updateOffset(javaY);

            if (changed) {
                plugin.getLogger().info(
                    "[BHO] Position packet → offset recalculé pour "
                    + player.getName()
                    + " : javaY=" + String.format("%.2f", javaY)
                    + " → offset=" + data.getOffset()
                );
            }

            plugin.getPluginConfig().debugLog(
                "POSITION packet pour " + player.getName()
                + " javaY=" + String.format("%.2f", javaY)
                + " offset=" + data.getOffset()
                + " bedrockY=" + String.format("%.2f", data.toBedrockY(javaY))
            );

        } catch (Exception e) {
            plugin.getPluginConfig().debugLog(
                "Erreur lecture POSITION packet: " + e.getMessage()
            );
        }
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    public void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
    }
}