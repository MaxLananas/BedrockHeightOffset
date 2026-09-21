package fr.buildtheearth.skywindow.translate;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.debug.DebugSubscriptions;
import org.geysermc.mcprotocollib.protocol.data.game.entity.RotationOrigin;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.TrailParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.VibrationParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.BlockPositionSource;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.EntityPositionSource;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.TrackedWaypoint;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.Vec3iWaypointData;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.WaypointOperation;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundGameTestHighlightPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.debug.ClientboundDebugBlockValuePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerLookAtPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelParticlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundOpenSignEditorPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundTrackedWaypointPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundBlockEntityTagQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundJigsawGeneratePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSetTestBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the extended packet set (the "a Bedrock builder must be able to do EVERYTHING at
 * altitude" contract): the player-teleport family with its relative-Y flags, block-position entity
 * metadata (beds, crystal beams), sign editing, look-at anchors, damage sources, death positions,
 * locator waypoints, and the full outbound tool set (signs, command blocks, jigsaws, structure
 * blocks, NBT queries, test blocks, unsigned chat commands).
 */
class ExtendedPacketCoverageTest {

    private static final CommandYRewrite.Config COMMANDS =
        new CommandYRewrite.Config(true, Set.of("tp", "tppos", "teleport"));

    @ParameterizedTest
    @ValueSource(ints = {16, 64, 1440})
    void playerPositionShiftsOnlyAbsoluteY(int offset) {
        double y = 1500.25;
        var absolute = new ClientboundPlayerPositionPacket(7,
            Vector3d.from(10, y, -10), Vector3d.from(0, -0.5, 0), 30f, 10f, List.of());
        var out = (ClientboundPlayerPositionPacket) InboundYTransforms.apply(absolute, offset);
        assertEquals(y - offset, out.getPosition().getY(), 0.0);
        assertEquals(10, out.getPosition().getX(), 0.0);
        assertEquals(-10, out.getPosition().getZ(), 0.0);
        // deltaMovement and the teleport id survive untouched: relative/state-by-id, never shifted.
        assertEquals(Vector3d.from(0, -0.5, 0), out.getDeltaMovement());
        assertEquals(7, out.getId());
        assertEquals(30f, out.getYRot());
        assertEquals(List.of(), out.getRelatives());

        // RELATIVE_Y means the Y field is a delta: the packet must be passed through by identity.
        var relative = new ClientboundPlayerPositionPacket(8,
            Vector3d.from(10, y, -10), Vector3d.ZERO, 0f, 0f, List.of(PositionElement.Y));
        assertSame(relative, InboundYTransforms.apply(relative, offset));
        var relativeAll = new ClientboundPlayerPositionPacket(9,
            Vector3d.from(10, y, -10), Vector3d.ZERO, 0f, 0f,
            List.of(PositionElement.X, PositionElement.Y, PositionElement.Z));
        assertSame(relativeAll, InboundYTransforms.apply(relativeAll, offset));
    }

    @Test
    void teleportEntityHonorsRelativeYToo() {
        int offset = 1440;
        var relative = new ClientboundTeleportEntityPacket(3,
            Vector3d.from(1, 2, 3), Vector3d.from(0, 0.2, 0), 0f, 0f, List.of(PositionElement.Y), true);
        assertSame(relative, InboundYTransforms.apply(relative, offset));
        // X/Z absolute + Y relative must stay untouched as well (mixed flags: the whole packet keeps
        // its raw fields; Geyser resolves relatives against the entity's current position).
        var mixed = new ClientboundTeleportEntityPacket(3,
            Vector3d.from(1, 2, 3), Vector3d.ZERO, 0f, 0f,
            List.of(PositionElement.Y, PositionElement.X_ROT), false);
        assertSame(mixed, InboundYTransforms.apply(mixed, offset));
    }

    @Test
    void blockPositionMetadataShiftsBedsBeamsAndHomes() {
        int offset = 64;
        Vector3i bed = Vector3i.from(5, 1901, -7);
        EntityMetadata<?, ?>[] metadata = new EntityMetadata<?, ?>[] {
            new ObjectEntityMetadata<>(0, MetadataTypes.BLOCK_POS, bed),
            new ObjectEntityMetadata<>(1, MetadataTypes.OPTIONAL_BLOCK_POS, Optional.of(bed)),
            new ObjectEntityMetadata<>(2, MetadataTypes.OPTIONAL_BLOCK_POS, Optional.empty()),
        };
        var packet = new ClientboundSetEntityDataPacket(33, metadata);
        var out = (ClientboundSetEntityDataPacket) InboundYTransforms.apply(packet, offset);

        assertEquals(bed.getY() - offset, ((Vector3i) out.getMetadata()[0].getValue()).getY());
        assertEquals(bed.getX(), ((Vector3i) out.getMetadata()[0].getValue()).getX());
        @SuppressWarnings("unchecked")
        Optional<Vector3i> shifted = (Optional<Vector3i>) out.getMetadata()[1].getValue();
        assertTrue(shifted.isPresent());
        assertEquals(bed.getY() - offset, shifted.get().getY());
        // Absent optionals and every other field survive by reference.
        assertSame(metadata[2], out.getMetadata()[2]);
        assertEquals(33, out.getEntityId());
    }

    @Test
    void signEditorLookAtDamageAndDeathPositionsShift() {
        int offset = 1440;

        var sign = new ClientboundOpenSignEditorPacket(Vector3i.from(1, 1951, 2), true);
        var signOut = (ClientboundOpenSignEditorPacket) InboundYTransforms.apply(sign, offset);
        assertEquals(1951 - offset, signOut.getPosition().getY());
        assertEquals(1, signOut.getPosition().getX());
        assertTrue(signOut.isFrontText());

        var look = new ClientboundPlayerLookAtPacket(RotationOrigin.FEET, 3, 1888.5, -4);
        var lookOut = (ClientboundPlayerLookAtPacket) InboundYTransforms.apply(look, offset);
        assertEquals(1888.5 - offset, lookOut.getY(), 0.0);
        assertEquals(3, lookOut.getX(), 0.0);
        assertEquals(RotationOrigin.FEET, lookOut.getOrigin());

        var damage = new ClientboundDamageEventPacket(5, 6, 7, 8, Vector3d.from(0, 1900.5, 0));
        var damageOut = (ClientboundDamageEventPacket) InboundYTransforms.apply(damage, offset);
        assertEquals(1900.5 - offset, damageOut.getSourcePosition().getY(), 0.0);
        assertEquals(5, damageOut.getEntityId());
        assertEquals(6, damageOut.getSourceTypeId());
        var noSource = new ClientboundDamageEventPacket(5, 6, 7, 8, null);
        assertSame(noSource, InboundYTransforms.apply(noSource, offset));

        PlayerSpawnInfo info = new PlayerSpawnInfo(0, Key.key("minecraft:overworld"), 42L,
            GameMode.SURVIVAL, null, false, false,
            new GlobalPos(Key.key("minecraft:overworld"), Vector3i.from(8, 1952, 9)), 0, 63);
        var login = new ClientboundLoginPacket(1, false, new Key[] {Key.key("minecraft:overworld")},
            20, 12, 8, true, true, false, info, false);
        var loginOut = (ClientboundLoginPacket) InboundYTransforms.apply(login, offset);
        assertEquals(1952 - offset, loginOut.getCommonPlayerSpawnInfo().getLastDeathPos().getPosition().getY());

        var respawn = new ClientboundRespawnPacket(info, true, false);
        var respawnOut = (ClientboundRespawnPacket) InboundYTransforms.apply(respawn, offset);
        assertEquals(1952 - offset, respawnOut.getCommonPlayerSpawnInfo().getLastDeathPos().getPosition().getY());
        assertEquals(Key.key("minecraft:overworld"),
            respawnOut.getCommonPlayerSpawnInfo().getLastDeathPos().getDimension());

        PlayerSpawnInfo noDeath = new PlayerSpawnInfo(0, Key.key("minecraft:overworld"), 42L,
            GameMode.SURVIVAL, GameMode.SURVIVAL, false, false, null, 0, 63);
        var loginNoDeath = new ClientboundLoginPacket(1, false, new Key[] {Key.key("minecraft:overworld")},
            20, 12, 8, true, true, false, noDeath, false);
        assertSame(loginNoDeath, InboundYTransforms.apply(loginNoDeath, offset));
    }

    @Test
    void trackedWaypointShiftsOnlyVec3iData() {
        int offset = 32;
        var icon = new TrackedWaypoint.Icon(Key.key("minecraft:default"), Optional.empty());
        var vec = new TrackedWaypoint(null, "base", icon, TrackedWaypoint.Type.VEC3I,
            new Vec3iWaypointData(Vector3i.from(4, 1900, 5)));
        var out = (ClientboundTrackedWaypointPacket) InboundYTransforms.apply(
            new ClientboundTrackedWaypointPacket(WaypointOperation.TRACK, vec), offset);
        Vec3iWaypointData data = (Vec3iWaypointData) out.getWaypoint().data();
        assertEquals(1900 - offset, data.vector().getY());
        assertEquals(TrackedWaypoint.Type.VEC3I, out.getWaypoint().type());
        assertEquals("base", out.getWaypoint().id());

        var azimuth = new TrackedWaypoint(null, "compass", icon, TrackedWaypoint.Type.AZIMUTH, null);
        var az = new ClientboundTrackedWaypointPacket(WaypointOperation.UPDATE, azimuth);
        assertSame(az, InboundYTransforms.apply(az, offset));
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 1440})
    void outboundToolPacketsShiftAndCommandsRewrite(int offset) {
        var query = new ServerboundBlockEntityTagQueryPacket(2, Vector3i.from(1, 505, 2));
        var queryOut = (ServerboundBlockEntityTagQueryPacket) OutboundYTransforms.apply(query, offset, COMMANDS);
        assertEquals(505 + offset, queryOut.getPosition().getY());

        var jigsaw = new ServerboundJigsawGeneratePacket(Vector3i.from(1, 505, 2), 3, true);
        var jigsawOut = (ServerboundJigsawGeneratePacket) OutboundYTransforms.apply(jigsaw, offset, COMMANDS);
        assertEquals(505 + offset, jigsawOut.getPosition().getY());
        assertEquals(3, jigsawOut.getLevels());

        var test = new ServerboundSetTestBlockPacket(Vector3i.from(1, 505, 2), 1, "hello");
        var testOut = (ServerboundSetTestBlockPacket) OutboundYTransforms.apply(test, offset, COMMANDS);
        assertEquals(505 + offset, testOut.getPosition().getY());

        String[] lines = {"a", "b", "c", "d"};
        var sign = new ServerboundSignUpdatePacket(Vector3i.from(1, 505, 2), lines, false);
        var signOut = (ServerboundSignUpdatePacket) OutboundYTransforms.apply(sign, offset, COMMANDS);
        assertEquals(505 + offset, signOut.getPosition().getY());
        assertArrayEquals(lines, signOut.getLines()); // text content untouched (the ctor copies the array)

        var cmd = new ServerboundChatCommandPacket("tp 12 505 -300");
        var cmdOut = (ServerboundChatCommandPacket) OutboundYTransforms.apply(cmd, offset, COMMANDS);
        assertEquals("tp 12 " + (505 + offset) + " -300", cmdOut.getCommand());

        // relative and out-of-allowlist commands keep their text byte-identical
        var rel = new ServerboundChatCommandPacket("tp 12 ~ ~-300");
        assertSame(rel, OutboundYTransforms.apply(rel, offset, COMMANDS));
    }

    @Test
    void freezeClassificationCoversEveryPositionBearingType() {
        assertTrue(OutboundYTransforms.isPositionBearing(
            new ServerboundSignUpdatePacket(Vector3i.ZERO, new String[] {"", "", "", ""}, true)));
        assertTrue(OutboundYTransforms.isPositionBearing(
            new ServerboundBlockEntityTagQueryPacket(1, Vector3i.ZERO)));
        assertTrue(OutboundYTransforms.isPositionBearing(
            new ServerboundJigsawGeneratePacket(Vector3i.ZERO, 1, false)));
        assertTrue(OutboundYTransforms.isPositionBearing(
            new ServerboundSetTestBlockPacket(Vector3i.ZERO, 0, "")));
        assertTrue(OutboundYTransforms.isPositionBearing(new ServerboundChatCommandPacket("tp 0 0 0")));
        assertTrue(OutboundYTransforms.isCommandPacket(new ServerboundChatCommandPacket("tp 0 0 0")));
        assertFalse(OutboundYTransforms.isCommandPacket(
            new ServerboundBlockEntityTagQueryPacket(1, Vector3i.ZERO)));

        assertEquals(Vector3i.ZERO,
            OutboundYTransforms.blockPosition(new ServerboundJigsawGeneratePacket(Vector3i.ZERO, 1, false)));
    }

    @Test
    void particleAbsoluteTargetsShiftButRelativeSourcesDoNot() {
        int offset = 1440;
        var trail = new TrailParticleData(Vector3d.from(2, 1948.5, -3), 0x00FF00, 40);
        var p1 = new ClientboundLevelParticlesPacket(
            new Particle(ParticleType.TRAIL, trail), true, false,
            1, 1947.0, 2, 0, 0, 0, 0, 0);
        var out1 = (ClientboundLevelParticlesPacket) InboundYTransforms.apply(p1, offset);
        assertEquals(1947.0 - offset, out1.getY(), 0.0);
        TrailParticleData t = (TrailParticleData) out1.getParticle().getData();
        assertEquals(1948.5 - offset, t.target().getY(), 0.0);
        assertEquals(2, t.target().getX(), 0.0);
        assertEquals(0x00FF00, t.color());
        assertEquals(40, t.duration());

        var vib = new VibrationParticleData(new BlockPositionSource(Vector3i.from(3, 1930, 4)), 12);
        var p2 = new ClientboundLevelParticlesPacket(
            new Particle(ParticleType.VIBRATION, vib), false, false,
            0, 10.0, 0, 0, 0, 0, 0, 0);
        var out2 = (ClientboundLevelParticlesPacket) InboundYTransforms.apply(p2, offset);
        VibrationParticleData v = (VibrationParticleData) out2.getParticle().getData();
        assertEquals(1930 - offset, ((BlockPositionSource) v.getPositionSource()).getPosition().getY());
        assertEquals(3, ((BlockPositionSource) v.getPositionSource()).getPosition().getX());
        assertEquals(12, v.getArrivalTicks());

        var entityVib = new VibrationParticleData(new EntityPositionSource(9, 0.25f), 12);
        var p3 = new ClientboundLevelParticlesPacket(
            new Particle(ParticleType.VIBRATION, entityVib), false, false,
            0, 10.0, 0, 0, 0, 0, 0, 0);
        var out3 = (ClientboundLevelParticlesPacket) InboundYTransforms.apply(p3, offset);
        // entity-relative vibration source: payload forwarded by reference, only y shifted
        assertSame(entityVib, out3.getParticle().getData());
    }

    @Test
    void debugAndGameTestHighlightPositionsShift() {
        int offset = 512;
        var debug = new ClientboundDebugBlockValuePacket(
            Vector3i.from(2, 1900, -2), DebugSubscriptions.BEES, null);
        var debugOut = (ClientboundDebugBlockValuePacket) InboundYTransforms.apply(debug, offset);
        assertEquals(1900 - offset, debugOut.getBlockPos().getY());
        assertEquals(2, debugOut.getBlockPos().getX());
        assertEquals(-2, debugOut.getBlockPos().getZ());
        assertEquals(DebugSubscriptions.BEES, debugOut.getSubscriptionType());

        var hl = new ClientboundGameTestHighlightPosPacket(
            Vector3i.from(0, 1800, 0), Vector3i.from(1, 2, 3));
        var hlOut = (ClientboundGameTestHighlightPosPacket) InboundYTransforms.apply(hl, offset);
        assertEquals(1800 - offset, hlOut.getAbsolutePos().getY());
        assertEquals(Vector3i.from(1, 2, 3), hlOut.getRelativePos());
    }

    @Test
    void zeroOffsetIsIdentityForTheWholeExtendedSet() {
        var sign = new ClientboundOpenSignEditorPacket(Vector3i.from(1, 2, 3), true);
        assertSame(sign, InboundYTransforms.apply(sign, 0));
        var cmd = new ServerboundChatCommandPacket("tp 1 2 3");
        assertSame(cmd, OutboundYTransforms.apply(cmd, 0, COMMANDS));
    }
}
