package fr.buildtheearth.skywindow.translate;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.mcprotocollib.protocol.data.game.entity.MinecartStep;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.TrailParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.VibrationParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.BlockPositionSource;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.TrackedWaypoint;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.Vec3iWaypointData;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.WaypointData;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundGameTestHighlightPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.debug.ClientboundDebugBlockValuePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveMinecartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerLookAtPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundTrackedWaypointPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server-to-client transforms: absolute-Y quantities move down by the active offset. Everything that
 * is relative (deltaMotion, motion, velocity, particle offsets, cursors, {@code PositionElement.Y}
 * flags on teleport-class packets) is deliberately NOT touched - shifting relative quantities is a
 * genuine desync bug class, see docs/ARCHITECTURE.md.
 *
 * <p>Every transform is {@code originalPacket.withX(newValue)} on mcprotocollib's immutable packets
 * (or an exact rebuild for the two packets without {@code @With}), so untouched packets are forwarded
 * by reference at zero cost.</p>
 *
 * <p><b>Relative-flags discipline:</b> {@code ClientboundPlayerPositionPacket} and
 * {@code ClientboundTeleportEntityPacket} may declare {@code PositionElement.Y} in their relatives
 * list, in which case the Y field is a <i>delta</i>, not a coordinate. Those packets keep their Y
 * byte-identical (Geyser's own translators add the current entity position for relative flags -
 * see {@code JavaPlayerPositionTranslator}/{@code JavaTeleportEntityTranslator}).</p>
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
        return apply(packet, offset, CommandYRewrite.Config.VANILLA_DEFAULT);
    }

    /** Same transform with the server's command-rewrite config (drives command-block display NBT). */
    public static Object apply(Object packet, int offset, CommandYRewrite.Config commands) {
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
            if (isRelativeY(p.getRelatives())) {
                return packet; // Y is a delta between entity positions - relative, never shifted
            }
            Vector3d pos = p.getPosition();
            return p.withPosition(Vector3d.from(pos.getX(), pos.getY() - offset, pos.getZ()));
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket p) {
            Vector3d pos = p.getPosition();
            return p.withPosition(Vector3d.from(pos.getX(), pos.getY() - offset, pos.getZ()));
        }
        if (packet instanceof ClientboundPlayerPositionPacket p) {
            // The player-teleport family (login spawn, /tp, portals, ender pearls, BTE warps).
            if (isRelativeY(p.getRelatives())) {
                return packet;
            }
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
            ClientboundBlockEntityDataPacket out = p.withPosition(shift(p.getPosition(), offset));
            // Command blocks: the stored command is real-space (the server runs it there), but a
            // windowed builder reopening the editor must SEE window-space coordinates - shift the
            // Command tag's Ys back down. Round-trips exactly with the SetCommandBlock edit path.
            NbtMap nbt = p.getNbt();
            if (nbt != null && commands.enabled() && nbt.get("Command") instanceof String command) {
                String rewritten = CommandYRewrite.rewrite(command, -offset, commands);
                if (rewritten != null) {
                    NbtMapBuilder builder = NbtMap.builder();
                    for (java.util.Map.Entry<String, Object> entry : nbt.entrySet()) {
                        if (!"Command".equals(entry.getKey())) {
                            builder.put(entry.getKey(), entry.getValue());
                        }
                    }
                    builder.put("Command", rewritten);
                    return out.withNbt(builder.build());
                }
            }
            return out;
        }
        if (packet instanceof ClientboundOpenSignEditorPacket p) {
            // Geyser forwards this straight into Bedrock's OpenSignPacket position: sign editing at
            // altitude must land on the windowed block or the editor opens on empty sky.
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ClientboundDebugBlockValuePacket p) {
            // Debug subscription values (bees, brains, pathing...) observe one block in the world.
            return p.withBlockPos(shift(p.getBlockPos(), offset));
        }
        if (packet instanceof ClientboundGameTestHighlightPosPacket p) {
            // absolutePos is a world coordinate; relativePos is relative to the tester and stays.
            return p.withAbsolutePos(shift(p.getAbsolutePos(), offset));
        }
        if (packet instanceof ClientboundLevelEventPacket p) {
            return p.withPosition(shift(p.getPosition(), offset));
        }
        if (packet instanceof ClientboundLevelParticlesPacket p) {
            ClientboundLevelParticlesPacket out = p.withY(p.getY() - offset);
            // Particle payload data can carry ABSOLUTE targets: trail beams end at a world position,
            // vibration/sculk pulses aim at a block (Geyser's JavaLevelParticlesTranslator consumes
            // the vibration target verbatim). Shift those; entity-relative sources and
            // color/item payloads are untouched.
            ParticleData data = p.getParticle().getData();
            ParticleData shiftedData = null;
            if (data instanceof TrailParticleData trail) {
                Vector3d target = trail.target();
                shiftedData = new TrailParticleData(
                    Vector3d.from(target.getX(), target.getY() - offset, target.getZ()),
                    trail.color(), trail.duration());
            } else if (data instanceof VibrationParticleData vibration
                && vibration.getPositionSource() instanceof BlockPositionSource blockSource) {
                shiftedData = new VibrationParticleData(
                    new BlockPositionSource(shift(blockSource.getPosition(), offset)),
                    vibration.getArrivalTicks());
            }
            return shiftedData == null
                ? out
                : out.withParticle(new Particle(p.getParticle().getType(), shiftedData));
        }
        if (packet instanceof ClientboundSoundPacket p) {
            return p.withY(p.getY() - offset);
        }
        if (packet instanceof ClientboundPlayerLookAtPacket p) {
            // Camera-rotation target ("look at" anchors): absolute feet/eyes-anchored point.
            return p.withY(p.getY() - offset);
        }
        if (packet instanceof ClientboundDamageEventPacket p) {
            // No @With on this packet: exact rebuild. sourcePosition is the damage source (knockback
            // tilt direction on Bedrock), nullable and absolute when present.
            Vector3d src = p.getSourcePosition();
            if (src == null) {
                return packet;
            }
            return new ClientboundDamageEventPacket(p.getEntityId(), p.getSourceTypeId(),
                p.getSourceCauseId(), p.getSourceDirectId(),
                Vector3d.from(src.getX(), src.getY() - offset, src.getZ()));
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
        if (packet instanceof ClientboundSetEntityDataPacket p) {
            // Bed position (sleeping at altitude), end-crystal beam target, creaking home: these are
            // the only absolute-position entity metadata values in the protocol. Display-entity
            // translation (Vector3f) is entity-local and stays untouched.
            return shiftPositionMetadata(p, offset);
        }
        if (packet instanceof ClientboundLoginPacket p) {
            // lastDeathPos is an absolute GlobalPos (death screen / recovery compass).
            PlayerSpawnInfo info = p.getCommonPlayerSpawnInfo();
            if (info.getLastDeathPos() == null) {
                return packet;
            }
            return p.withCommonPlayerSpawnInfo(shiftSpawnInfo(info, offset));
        }
        if (packet instanceof ClientboundRespawnPacket p) {
            PlayerSpawnInfo info = p.getCommonPlayerSpawnInfo();
            if (info.getLastDeathPos() == null) {
                return packet;
            }
            return p.withCommonPlayerSpawnInfo(shiftSpawnInfo(info, offset));
        }
        if (packet instanceof ClientboundTrackedWaypointPacket p) {
            // Locator-bar waypoints: only the VEC3I flavour carries an absolute block position.
            TrackedWaypoint wp = p.getWaypoint();
            if (wp.type() != TrackedWaypoint.Type.VEC3I || !(wp.data() instanceof Vec3iWaypointData vec)) {
                return packet;
            }
            TrackedWaypoint shifted = new TrackedWaypoint(wp.uuid(), wp.id(), wp.icon(), wp.type(),
                new Vec3iWaypointData(shift(vec.vector(), offset)));
            return p.withWaypoint(shifted);
        }
        return packet;
    }

    /** True when the packet's relatives list declares Y as relative (a delta, not a coordinate). */
    public static boolean isRelativeY(List<PositionElement> relatives) {
        for (int i = 0; i < relatives.size(); i++) {
            if (relatives.get(i) == PositionElement.Y) {
                return true;
            }
        }
        return false;
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

    private static Vector3i shift(Vector3i v, int offset) {
        return Vector3i.from(v.getX(), v.getY() - offset, v.getZ());
    }

    private static BlockChangeEntry shiftEntry(BlockChangeEntry entry, int offset) {
        return new BlockChangeEntry(shift(entry.getPosition(), offset), entry.getBlock());
    }

    private static PlayerSpawnInfo shiftSpawnInfo(PlayerSpawnInfo info, int offset) {
        GlobalPos death = info.getLastDeathPos();
        return new PlayerSpawnInfo(info.getDimension(), info.getWorldName(), info.getHashedSeed(),
            info.getGameMode(), info.getPreviousGamemode(), info.isDebug(), info.isFlat(),
            death == null ? null : new GlobalPos(death.getDimension(), shift(death.getPosition(), offset)),
            info.getPortalCooldown(), info.getSeaLevel());
    }

    /**
     * Rebuilds {@code ClientboundSetEntityDataPacket} with every absolute block-position metadata
     * value shifted. Matching is by {@link MetadataTypes} identity (the wire decoder builds entries
     * from these singletons); any other metadata - including display-entity local translations and
     * rotations - is reused by reference.
     */
    private static ClientboundSetEntityDataPacket shiftPositionMetadata(ClientboundSetEntityDataPacket p, int offset) {
        EntityMetadata<?, ?>[] in = p.getMetadata();
        EntityMetadata<?, ?>[] out = null;
        for (int i = 0; i < in.length; i++) {
            EntityMetadata<?, ?> entry = in[i];
            Object type = entry.getType();
            Object value = entry.getValue();
            if (type == MetadataTypes.BLOCK_POS && value instanceof Vector3i pos) {
                if (out == null) {
                    out = in.clone();
                }
                out[i] = new ObjectEntityMetadata<>(entry.getId(), MetadataTypes.BLOCK_POS, shift(pos, offset));
            } else if (type == MetadataTypes.OPTIONAL_BLOCK_POS && value instanceof Optional<?> opt
                && opt.isPresent() && opt.get() instanceof Vector3i pos) {
                if (out == null) {
                    out = in.clone();
                }
                out[i] = new ObjectEntityMetadata<>(entry.getId(), MetadataTypes.OPTIONAL_BLOCK_POS,
                    Optional.of(shift(pos, offset)));
            }
        }
        return out == null ? p : p.withMetadata(out);
    }
}
