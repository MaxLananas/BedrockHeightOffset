package io.github.maxlananas.hauteur.translate;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.MinecartStep;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveMinecartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerLookAtPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockDestructionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundExplodePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelParticlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundOpenSignEditorPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetDefaultSpawnPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client transforms: absolute-Y quantities move down by the active offset. Everything that
 * is relative (deltaMovement, motion, velocity, particle offsets, cursors) is deliberately NOT touched -
 * shifting relative quantities is a genuine desync bug class, see docs/ARCHITECTURE.md.
 *
 * <p>Every transform is {@code originalPacket.withX(newValue)} on mcprotocollib's immutable packets, so
 * untouched packets are forwarded by reference at zero cost.</p>
 */
public final class InboundYTransforms {
    private InboundYTransforms() {
    }

    /**
     * Single-pass transform: callers pass every packet and compare the result by reference
     * ({@code != packet} means "was translated"). Unhandled types fall out of the chain untouched -
     * a separate predicate pass over the same instanceof chain would double the type checks on
     * every packet for no other benefit.
     *
     * @return the transformed packet, or {@code packet} itself when nothing changed
     */
    public static Object apply(Object packet, int offset) {
        if (offset == 0) {
            return packet;
        }
        if (packet instanceof ClientboundBlockUpdatePacket p) {
            return p.withEntry(shiftEntry(p.getEntry(), offset));
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket p) {
            BlockChangeEntry[] in = p.getEntries();
            BlockChangeEntry[] out = new BlockChangeEntry[in.length];
            for (int i = 0; i < in.length; i++) {
                out[i] = shiftEntry(in[i], offset);
            }
            return p.withChunkY(p.getChunkY() - (offset >> 4)).withEntries(out);
        }
        if (packet instanceof ClientboundAddEntityPacket p) {
            return p.withY(p.getY() - offset);
        }
        if (packet instanceof ClientboundMoveVehiclePacket p) {
            Vector3d pos = p.getPosition();
            return p.withPosition(Vector3d.from(pos.getX(), pos.getY() - offset, pos.getZ()));
        }
        if (packet instanceof ClientboundMoveMinecartPacket p) {
            return p.withLerpSteps(shiftSteps(p.getLerpSteps(), offset));
        }
        if (packet instanceof ClientboundTeleportEntityPacket p) {
            return p.withPosition(shiftAbsoluteY(p.getPosition(), p.getRelatives(), offset));
        }
        if (packet instanceof ClientboundPlayerPositionPacket p) {
            return p.withPosition(shiftAbsoluteY(p.getPosition(), p.getRelatives(), offset));
        }
        if (packet instanceof ClientboundPlayerLookAtPacket p) {
            // The look target is always absolute; the entity origin variants do not change this.
            return p.withY(p.getY() - offset);
        }
        if (packet instanceof ClientboundOpenSignEditorPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket p) {
            Vector3d pos = p.getPosition();
            return p.withPosition(Vector3d.from(pos.getX(), pos.getY() - offset, pos.getZ()));
        }
        if (packet instanceof ClientboundBlockEventPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ClientboundBlockDestructionPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ClientboundBlockEntityDataPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ClientboundLevelEventPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ClientboundLevelParticlesPacket p) {
            return p.withY(p.getY() - offset);
        }
        if (packet instanceof ClientboundSoundPacket p) {
            return p.withY(p.getY() - offset);
        }
        if (packet instanceof ClientboundExplodePacket p) {
            Vector3d c = p.getCenter();
            // playerKnockback is a velocity delta: relative, never shifted.
            return p.withCenter(Vector3d.from(c.getX(), c.getY() - offset, c.getZ()));
        }
        if (packet instanceof ClientboundSetDefaultSpawnPositionPacket p) {
            GlobalPos gp = p.getGlobalPos();
            return p.withGlobalPos(new GlobalPos(gp.getDimension(), shift(gp.getPosition(), offset)));
        }
        return packet;
    }

    private static List<MinecartStep> shiftSteps(List<MinecartStep> steps, int offset) {
        List<MinecartStep> out = new ArrayList<>(steps.size());
        for (MinecartStep s : steps) {
            Vector3d pos = s.position();
            out.add(new MinecartStep(Vector3d.from(pos.getX(), pos.getY() - offset, pos.getZ()),
                s.movement(), s.yRot(), s.xRot(), s.weight()));
        }
        return out;
    }

    /**
     * A teleport's Y is absolute unless the packet flags Y as relative, in which case it is a delta from the
     * entity's current position. Deltas are relative quantities and are never shifted.
     */
    private static Vector3d shiftAbsoluteY(Vector3d pos, List<PositionElement> relatives, int offset) {
        if (relatives.contains(PositionElement.Y)) {
            return pos;
        }
        return Vector3d.from(pos.getX(), pos.getY() - offset, pos.getZ());
    }

    private static Vector3i shift(Vector3i v, int offset) {
        return Vector3i.from(v.getX(), v.getY() - offset, v.getZ());
    }

    private static BlockChangeEntry shiftEntry(BlockChangeEntry entry, int offset) {
        return new BlockChangeEntry(shift(entry.getPosition(), offset), entry.getBlock());
    }
}
