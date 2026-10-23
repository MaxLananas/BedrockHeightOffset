package fr.buildtheearth.skywindow.translate;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandSignedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCommandBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetJigsawBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetStructureBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundBlockEntityTagQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundJigsawGeneratePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSetTestBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundTestInstanceBlockActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

/**
 * Client-to-server transforms: every absolute-Y quantity the Bedrock client sends (always in window
 * space) moves back up by the active offset. Relative quantities (cursor position within a block, look
 * direction, structure offsets and sizes, velocities) are never touched.
 *
 * <p>The transform is exact integer arithmetic on block coordinates and exact double addition on
 * positions. Composed with {@link InboundYTransforms} it is the identity; {@code TransformPropertyTest}
 * checks this.</p>
 *
 * <p>{@link #frozenDisposition(Object)} is the single place that decides what happens to an outbound packet
 * during a window switch. The table in docs/PACKET-MATRIX.md mirrors it.</p>
 */
public final class OutboundYTransforms {

    private OutboundYTransforms() {
    }

    /**
     * What to do with an outbound packet while the client may still be in the previous frame.
     */
    public enum FrozenDisposition {
        /** Carries no absolute Y: frame-independent, forwarded unchanged. */
        TRANSMIT,
        /** Player movement or vehicle movement: held and resolved against the anchor on unfreeze. */
        HOLD_MOVEMENT,
        /** Block-positioned action: held and resolved by plausibility of the block position. */
        HOLD_BLOCK_ACTION,
        /** Cannot be placed in a frame without guessing (e.g. a command with coordinates): dropped. */
        DROP
    }

    /** Classifies an outbound packet for the freeze window. Pure: depends only on the packet type and fields. */
    public static FrozenDisposition frozenDisposition(Object packet) {
        if (packet instanceof ServerboundMovePlayerPosPacket
            || packet instanceof ServerboundMovePlayerPosRotPacket
            || packet instanceof ServerboundMoveVehiclePacket) {
            return FrozenDisposition.HOLD_MOVEMENT;
        }
        if (packet instanceof ServerboundPlayerActionPacket
            || packet instanceof ServerboundUseItemOnPacket
            || packet instanceof ServerboundPickItemFromBlockPacket
            || packet instanceof ServerboundSignUpdatePacket
            || packet instanceof ServerboundSetCommandBlockPacket
            || packet instanceof ServerboundSetStructureBlockPacket
            || packet instanceof ServerboundSetJigsawBlockPacket
            || packet instanceof ServerboundJigsawGeneratePacket
            || packet instanceof ServerboundBlockEntityTagQueryPacket
            || packet instanceof ServerboundSetTestBlockPacket
            || packet instanceof ServerboundTestInstanceBlockActionPacket) {
            return FrozenDisposition.HOLD_BLOCK_ACTION;
        }
        if (packet instanceof ServerboundChatCommandSignedPacket signed) {
            // Unsigned commands are rewritten (see apply), so their coordinates cannot be resolved while the
            // frame is uncertain. Signed commands are never rewritten and are not frame-dependent.
            return signed.getSignatures().isEmpty() ? FrozenDisposition.DROP : FrozenDisposition.TRANSMIT;
        }
        return FrozenDisposition.TRANSMIT;
    }

    /**
     * Block position a block-action packet refers to, in the frame the client wrote it in.
     *
     * @return the position, or null when the packet is not a block action
     */
    public static Vector3i blockPosition(Object packet) {
        if (packet instanceof ServerboundPlayerActionPacket p) {
            return p.getPosition();
        }
        if (packet instanceof ServerboundUseItemOnPacket p) {
            return p.getPosition();
        }
        if (packet instanceof ServerboundPickItemFromBlockPacket p) {
            return p.getPos();
        }
        if (packet instanceof ServerboundSignUpdatePacket p) {
            return p.getPosition();
        }
        if (packet instanceof ServerboundSetCommandBlockPacket p) {
            return p.getPosition();
        }
        if (packet instanceof ServerboundSetStructureBlockPacket p) {
            return p.getPosition();
        }
        if (packet instanceof ServerboundSetJigsawBlockPacket p) {
            return p.getPosition();
        }
        if (packet instanceof ServerboundJigsawGeneratePacket p) {
            return p.getPosition();
        }
        if (packet instanceof ServerboundBlockEntityTagQueryPacket p) {
            return p.getPosition();
        }
        if (packet instanceof ServerboundSetTestBlockPacket p) {
            return p.getPosition();
        }
        if (packet instanceof ServerboundTestInstanceBlockActionPacket p) {
            return p.getPos();
        }
        return null;
    }

    /**
     * Y value of a player or vehicle movement packet, in the frame it was written in.
     *
     * @return the Y, or {@link Double#NaN} when the packet carries no absolute Y
     */
    public static double movementY(Object packet) {
        if (packet instanceof ServerboundMovePlayerPosPacket p) {
            return p.getY();
        }
        if (packet instanceof ServerboundMovePlayerPosRotPacket p) {
            return p.getY();
        }
        if (packet instanceof ServerboundMoveVehiclePacket p) {
            return p.getPosition().getY();
        }
        return Double.NaN;
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
        if (packet instanceof ServerboundJigsawGeneratePacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ServerboundBlockEntityTagQueryPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ServerboundSetTestBlockPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ServerboundTestInstanceBlockActionPacket p) {
            return p.withPos(shift(p.getPos(), offset));
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
