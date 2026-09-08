package fr.buildtheearth.skywindow.translate;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandSignedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCommandBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetJigsawBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetStructureBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

/**
 * Client-to-server transforms: every absolute-Y quantity the Bedrock client sends (always in window
 * space) moves back up by the active offset. Relative quantities (cursor position within a block,
 * look direction, structure offsets/sizes, velocities) are never touched. The transform is exact
 * integer arithmetic on block coordinates and exact double addition on positions, so
 * {@code InboundYTransforms} composed with this class is the identity - this is asserted by tests at
 * the values in the requirement list (Y = -64, 0, 319, 320, 510, 1000, 1500, 1952 and beyond, plus the
 * negative range).
 */
public final class OutboundYTransforms {
    private OutboundYTransforms() {
    }

    /** Packets that carry a player-controlled position; they are frozen during a window switch. */
    public static boolean isPositionBearing(Object packet) {
        return packet instanceof ServerboundMovePlayerPosPacket
            || packet instanceof ServerboundMovePlayerPosRotPacket
            || packet instanceof ServerboundPlayerActionPacket
            || packet instanceof ServerboundUseItemOnPacket
            || packet instanceof ServerboundMoveVehiclePacket;
    }

    /** @return the transformed packet, or {@code packet} itself when nothing changed */
    public static Object apply(Object packet, int offset, CommandYRewrite.Config commands) {
        if (offset == 0) {
            return packet;
        }
        if (packet instanceof ServerboundMovePlayerPosPacket p) {
            return p.withY(p.getY() + offset);
        }
        if (packet instanceof ServerboundMovePlayerPosRotPacket p) {
            return p.withY(p.getY() + offset);
        }
        if (packet instanceof ServerboundPlayerActionPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ServerboundUseItemOnPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ServerboundMoveVehiclePacket p) {
            Vector3d pos = p.getPosition();
            return p.withPosition(Vector3d.from(pos.getX(), pos.getY() + offset, pos.getZ()));
        }
        if (packet instanceof ServerboundPickItemFromBlockPacket p) {
            return p.withPos(shift(p.getPos(), offset));
        }
        if (packet instanceof ServerboundSignUpdatePacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ServerboundSetCommandBlockPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ServerboundSetStructureBlockPacket p) {
            // Only the target position is absolute; offset and size are relative.
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ServerboundSetJigsawBlockPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ServerboundChatCommandSignedPacket p && commands.enabled() && p.getSignatures().isEmpty()) {
            // Rewriting a signed command would invalidate its signature; unsigned commands (what Geyser
            // clients send) are safe to adjust.
            String rewritten = CommandYRewrite.rewrite(p.getCommand(), offset, commands);
            if (rewritten != null) {
                return p.withCommand(rewritten);
            }
        }
        return packet;
    }

    private static Vector3i shift(Vector3i v, int offset) {
        return Vector3i.from(v.getX(), v.getY() + offset, v.getZ());
    }
}
