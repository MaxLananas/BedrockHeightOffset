package fr.buildtheearth.skywindow.translate;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.BlockBreakStage;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockDestructionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Property-style checks over the transform tables: for randomized coordinates, the Y shift must be
 * exactly the offset (both directions), every other field must survive untouched (relative quantities
 * included), and packets outside the tables must pass through by reference. This complements
 * {@code dev/verify/oracle.py}, which mirrors the same properties in Python for offline runs.
 */
class TransformPropertyTest {

    private static final CommandYRewrite.Config NO_COMMANDS =
        new CommandYRewrite.Config(false, Set.of());

    @ParameterizedTest
    @ValueSource(ints = {-1000, -64, 0, 1, 319, 320, 511, 512, 900, 1440})
    void offsetRangeRoundTripsEverything(int offset) {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            double clientY = rnd.nextInt(-4000, 4000);
            double x = rnd.nextInt(-20_000_000, 20_000_000) + rnd.nextDouble();
            double z = rnd.nextInt(-20_000_000, 20_000_000) + rnd.nextDouble();

            // Movement in: client Y (window space) must become exactly +offset on the wire.
            var move = new ServerboundMovePlayerPosPacket(true, false, x, clientY, z);
            var moveOut = (ServerboundMovePlayerPosPacket)
                OutboundYTransforms.apply(move, offset, NO_COMMANDS);
            if (offset == 0) {
                assertSame(move, moveOut);
            } else {
                assertEquals(clientY + offset, moveOut.getY(), 0.0);
                assertEquals(x, moveOut.getX(), 0.0);
                assertEquals(z, moveOut.getZ(), 0.0);
                assertEquals(move.isOnGround(), moveOut.isOnGround());
                assertEquals(move.isHorizontalCollision(), moveOut.isHorizontalCollision());
            }

            // Teleport out: server real Y must become exactly -offset toward the client.
            var tp = new ClientboundTeleportEntityPacket(42,
                Vector3d.from(x, clientY + offset, z), Vector3d.from(1, 2, 3),
                12.5f, -3.25f, List.of(), true);
            var tpIn = (ClientboundTeleportEntityPacket) InboundYTransforms.apply(tp, offset);
            Vector3d expected = offset == 0 ? tp.getPosition()
                : Vector3d.from(x, clientY + offset - offset, z);
            assertEquals(expected.getX(), tpIn.getPosition().getX(), 0.0);
            assertEquals(expected.getY(), tpIn.getPosition().getY(), 0.0);
            assertEquals(expected.getZ(), tpIn.getPosition().getZ(), 0.0);
            // deltaMovement is relative: must be byte-identical no matter the offset.
            assertEquals(Vector3d.from(1, 2, 3), tpIn.getDeltaMovement());
            assertEquals(tp.getYRot(), tpIn.getYRot());
            assertEquals(tp.getXRot(), tpIn.getXRot());
            assertEquals(tp.isOnGround(), tpIn.isOnGround());
        }
    }

    @Test
    void blockActionsShiftPositionOnlyAndKeepFaceSequence() {
        Random rnd = new Random(11);
        for (int trial = 0; trial < 100; trial++) {
            int y = rnd.nextInt(-4000, 4000);
            int x = rnd.nextInt();
            int z = rnd.nextInt();
            int offset = rnd.nextInt(0, 1441) & ~15;

            var action = new ServerboundPlayerActionPacket(
                PlayerAction.START_DIGGING, Vector3i.from(x, y, z), Direction.UP, 17);
            var out = (ServerboundPlayerActionPacket) OutboundYTransforms.apply(action, offset, NO_COMMANDS);
            assertEquals(x, out.getPosition().getX());
            assertEquals(y + offset, out.getPosition().getY());
            assertEquals(z, out.getPosition().getZ());
            assertEquals(Direction.UP, out.getFace());
            assertEquals(17, out.getSequence());

            var use = new ServerboundUseItemOnPacket(Vector3i.from(x, y, z), Direction.NORTH, Hand.OFF_HAND,
                0.25f, 0.5f, 0.75f, true, false, 9);
            var useOut = (ServerboundUseItemOnPacket) OutboundYTransforms.apply(use, offset, NO_COMMANDS);
            assertEquals(y + offset, useOut.getPosition().getY());
            // cursors are inside-block offsets: relative, never touched
            assertEquals(0.5f, useOut.getCursorY());
            assertEquals(0.25f, useOut.getCursorX());
            assertEquals(Direction.NORTH, useOut.getFace());
            assertEquals(9, useOut.getSequence());

            var crack = new ClientboundBlockDestructionPacket(3, Vector3i.from(x, y, z), BlockBreakStage.STAGE_5);
            var crackIn = (ClientboundBlockDestructionPacket) InboundYTransforms.apply(crack, offset);
            assertEquals(y - offset, crackIn.getPosition().getY());
            assertEquals(3, crackIn.getBreakerEntityId());
        }
    }

    @Test
    void blockUpdateEntriesShiftAndKeepBlockId() {
        Random rnd = new Random(13);
        for (int trial = 0; trial < 100; trial++) {
            int y = rnd.nextInt(-4000, 4000);
            int offset = rnd.nextInt(0, 1441) & ~15;
            var entry = new BlockChangeEntry(Vector3i.from(rnd.nextInt(), y, rnd.nextInt()), 1234);
            var packet = new ClientboundBlockUpdatePacket(entry);
            var shifted = (ClientboundBlockUpdatePacket) InboundYTransforms.apply(packet, offset);
            assertEquals(y - offset, shifted.getEntry().getPosition().getY());
            assertEquals(entry.getPosition().getX(), shifted.getEntry().getPosition().getX());
            assertEquals(1234, shifted.getEntry().getBlock());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 64, 1440})
    void positionlessPacketsAlwaysPassThroughByIdentity(int offset) {
        var rotOnly = new ServerboundMovePlayerRotPacket(true, false, 90.0f, 10.0f);
        assertSame(rotOnly, OutboundYTransforms.apply(rotOnly, offset, NO_COMMANDS));
        var look = new ServerboundMovePlayerPosRotPacket(true, false, 1, 2, 3, 90.0f, 10.0f);
        // the posRot variant DOES carry a position and must translate instead - pinning both sides
        var lookOut = (ServerboundMovePlayerPosRotPacket) OutboundYTransforms.apply(look, offset, NO_COMMANDS);
        assertEquals(2 + offset, lookOut.getY(), 0.0);
        assertEquals(1, lookOut.getX(), 0.0);
        assertEquals(90.0f, lookOut.getYaw());
        assertEquals(10.0f, lookOut.getPitch());
        var chunk = new org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level
            .ClientboundForgetLevelChunkPacket(4, -9);
        assertSame(chunk, InboundYTransforms.apply(chunk, offset));
    }
}
