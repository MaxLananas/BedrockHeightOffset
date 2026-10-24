package io.github.maxlananas.hauteur.translate;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandSignedPacket;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage of the packet classes that carry absolute Y, and of the freeze classification. Each case pins
 * one decision from docs/PACKET-MATRIX.md, so a change to the table must change this test too.
 */
class PositionCoverageTest {

    private static final CommandYRewrite.Config NO_COMMANDS = new CommandYRewrite.Config(false, Set.of());

    @Test
    void movementIsHeldNotTransmitted() {
        assertEquals(OutboundYTransforms.FrozenDisposition.HOLD_MOVEMENT,
            OutboundYTransforms.frozenDisposition(new ServerboundMovePlayerPosRotPacket(true, false, 1, 2, 3, 90f, 10f)));
    }

    @Test
    void blockInteractionsAreHeldAsBlockActions() {
        var dig = new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, Vector3i.from(1, 600, 1), Direction.UP, 0);
        var use = new ServerboundUseItemOnPacket(Vector3i.from(1, 600, 1), Direction.NORTH, Hand.OFF_HAND,
            0.25f, 0.5f, 0.75f, true, false, 9);
        assertEquals(OutboundYTransforms.FrozenDisposition.HOLD_BLOCK_ACTION, OutboundYTransforms.frozenDisposition(dig));
        assertEquals(OutboundYTransforms.FrozenDisposition.HOLD_BLOCK_ACTION, OutboundYTransforms.frozenDisposition(use));
    }

    @Test
    void nonDiggingPlayerActionsCarryNoBlockAndAreNeitherShiftedNorHeld() {
        // Geyser sends these with Vector3i.ZERO. The zero is not a block: it must not be shifted, held, or
        // resolved by plausibility, or a release held across a switch would be dropped.
        for (PlayerAction action : new PlayerAction[] {PlayerAction.RELEASE_USE_ITEM, PlayerAction.DROP_ITEM,
            PlayerAction.DROP_ITEM_STACK, PlayerAction.SWAP_HANDS, PlayerAction.STAB}) {
            var packet = new ServerboundPlayerActionPacket(action, Vector3i.ZERO, Direction.DOWN, 3);
            assertEquals(OutboundYTransforms.FrozenDisposition.TRANSMIT,
                OutboundYTransforms.frozenDisposition(packet), action.name());
            assertNull(OutboundYTransforms.blockPosition(packet), action.name());
            assertSame(packet, OutboundYTransforms.apply(packet, 1000, CommandYRewrite.Config.DISABLED), action.name());
        }
    }

    @Test
    void dropFromTheBlockBreakActionNamesItsBlockAndIsShifted() {
        // Geyser copies the client's block position into this drop (BlockBreakHandler). It is a block in the
        // client frame, so it is shifted and held like a dig. The zero-position drop above is not.
        var drop = new ServerboundPlayerActionPacket(PlayerAction.DROP_ITEM, Vector3i.from(1, 600, 1), Direction.UP, 0);
        assertEquals(OutboundYTransforms.FrozenDisposition.HOLD_BLOCK_ACTION, OutboundYTransforms.frozenDisposition(drop));
        assertEquals(Vector3i.from(1, 600, 1), OutboundYTransforms.blockPosition(drop));
        var shifted = (ServerboundPlayerActionPacket) OutboundYTransforms.apply(drop, 1000, CommandYRewrite.Config.DISABLED);
        assertEquals(1600, shifted.getPosition().getY());
    }

    @Test
    void diggingStillNamesABlockAndIsShifted() {
        var dig = new ServerboundPlayerActionPacket(PlayerAction.FINISH_DIGGING, Vector3i.from(1, 600, 1), Direction.UP, 0);
        assertEquals(OutboundYTransforms.FrozenDisposition.HOLD_BLOCK_ACTION, OutboundYTransforms.frozenDisposition(dig));
        assertEquals(Vector3i.from(1, 600, 1), OutboundYTransforms.blockPosition(dig));
        var shifted = (ServerboundPlayerActionPacket) OutboundYTransforms.apply(dig, 1000, CommandYRewrite.Config.DISABLED);
        assertEquals(1600, shifted.getPosition().getY());
    }

    @Test
    void packetsWithoutAbsoluteYPassThroughFreeze() {
        assertEquals(OutboundYTransforms.FrozenDisposition.TRANSMIT,
            OutboundYTransforms.frozenDisposition(new ServerboundAcceptTeleportationPacket(7)));
    }

    @Test
    void unsignedCommandsAreDroppedWhileTheFrameIsUncertain() {
        var unsigned = new ServerboundChatCommandSignedPacket("tp 0 600 0", 0L, 0L, Collections.emptyList(), 0,
            new BitSet(), (byte) 0);
        assertEquals(OutboundYTransforms.FrozenDisposition.DROP, OutboundYTransforms.frozenDisposition(unsigned));
    }

    @Test
    void plainChatCommandIsDroppedWhileFrozenAndRewrittenOtherwise() {
        var plain = new ServerboundChatCommandPacket("tp 0 600 0");
        assertEquals(OutboundYTransforms.FrozenDisposition.DROP, OutboundYTransforms.frozenDisposition(plain));
        var rewrite = new CommandYRewrite.Config(true, Set.of("tp"));
        var out = (ServerboundChatCommandPacket) OutboundYTransforms.apply(plain, 1440, rewrite);
        assertEquals("tp 0 2040 0", out.getCommand());
    }

    @Test
    void blockPositionIsExposedForEveryBlockActionAndNullOtherwise() {
        var dig = new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, Vector3i.from(4, 600, 5), Direction.UP, 0);
        assertEquals(Vector3i.from(4, 600, 5), OutboundYTransforms.blockPosition(dig));
        assertNull(OutboundYTransforms.blockPosition(new ServerboundAcceptTeleportationPacket(7)));
    }

    @Test
    void movementYIsNaNForPacketsWithoutAbsoluteY() {
        assertEquals(12.5, OutboundYTransforms.movementY(new ServerboundMovePlayerPosRotPacket(true, false, 1, 12.5, 3, 90f, 10f)));
        assertTrue(Double.isNaN(OutboundYTransforms.movementY(new ServerboundAcceptTeleportationPacket(7))));
    }

    @Test
    void absolutePlayerPositionIsShiftedIntoWindowSpace() {
        var pos = new ClientboundPlayerPositionPacket(3, Vector3d.from(10.5, 1500, -2.25), Vector3d.ZERO, 0f, 0f,
            List.of());
        var out = (ClientboundPlayerPositionPacket) InboundYTransforms.apply(pos, 1440);
        assertEquals(60.0, out.getPosition().getY(), 0.0);
        assertEquals(10.5, out.getPosition().getX(), 0.0);
        assertEquals(-2.25, out.getPosition().getZ(), 0.0);
    }

    @Test
    void relativePlayerPositionDeltaIsNeverShifted() {
        // /tp ~ ~5 ~ style: the Y is a delta from the current position, a relative quantity.
        var pos = new ClientboundPlayerPositionPacket(3, Vector3d.from(0, 5, 0), Vector3d.ZERO, 0f, 0f,
            List.of(PositionElement.X, PositionElement.Y, PositionElement.Z));
        assertSame(pos, InboundYTransforms.apply(pos, 1440));
    }

    @Test
    void relativeTeleportEntityDeltaIsNeverShifted() {
        var tp = new ClientboundTeleportEntityPacket(42, Vector3d.from(0, 5, 0), Vector3d.ZERO, 0f, 0f,
            List.of(PositionElement.Y), true);
        assertSame(tp, InboundYTransforms.apply(tp, 1440));
    }

    @Test
    void absoluteTeleportEntityIsShifted() {
        var tp = new ClientboundTeleportEntityPacket(42, Vector3d.from(0, 1500, 0), Vector3d.ZERO, 0f, 0f,
            List.of(), true);
        var out = (ClientboundTeleportEntityPacket) InboundYTransforms.apply(tp, 1440);
        assertEquals(60.0, out.getPosition().getY(), 0.0);
    }

    @Test
    void absoluteDamageSourcePositionIsShifted() {
        // The hurt direction points at a real-space place; in the window it must sit at the same place.
        var hit = new ClientboundDamageEventPacket(7, 2, 3, 4, Vector3d.from(10.5, 1500, -2.25));
        var out = (ClientboundDamageEventPacket) InboundYTransforms.apply(hit, 1440);
        assertEquals(60.0, out.getSourcePosition().getY(), 0.0);
        assertEquals(10.5, out.getSourcePosition().getX(), 0.0);
        assertEquals(-2.25, out.getSourcePosition().getZ(), 0.0);
        assertEquals(7, out.getEntityId());
        assertEquals(3, out.getSourceCauseId());
    }

    @Test
    void damageWithoutSourcePositionIsUntouched() {
        var hit = new ClientboundDamageEventPacket(7, 2, -1, -1, null);
        assertSame(hit, InboundYTransforms.apply(hit, 1440));
    }

    @Test
    void zeroOffsetIsAnIdentityForEveryPacket() {
        var pos = new ClientboundPlayerPositionPacket(3, Vector3d.from(0, 1500, 0), Vector3d.ZERO, 0f, 0f, List.of());
        assertSame(pos, InboundYTransforms.apply(pos, 0));
    }
}
