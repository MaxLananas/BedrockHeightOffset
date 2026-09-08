package fr.buildtheearth.skywindow.pipeline;

import fr.buildtheearth.skywindow.SkyWindowConfig;
import fr.buildtheearth.skywindow.SkyWindowCore;
import fr.buildtheearth.skywindow.session.SkyWindowSession;
import fr.buildtheearth.skywindow.translate.CommandYRewrite;
import fr.buildtheearth.skywindow.translate.InboundYTransforms;
import fr.buildtheearth.skywindow.translate.OutboundYTransforms;
import fr.buildtheearth.skywindow.translate.WindowedChunks;
import fr.buildtheearth.skywindow.window.WindowRules;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Single translation choke point for one Bedrock player. Installed on Geyser's Java-side pipeline
 * directly after mcprotocollib's {@code codec} handler, so inbound packets are seen freshly decoded
 * (server/real space) and outbound packets are seen right before serialization (client/window space).
 * All absolute-Y translation for the session happens here, once - Geyser's translators, the Bedrock
 * protocol and the Java server all keep speaking their normal coordinate systems, and the offset never
 * passes through more than one layer.
 *
 * <p>Threading: inbound and the whole switch run on the channel event loop, so everything below that
 * is not explicitly volatile is confined to it. Outbound {@code write} may run on the Geyser tick
 * loop; there, state is only read (volatiles) and decisions are bounced to the event loop.</p>
 */
public final class SkyWindowHandler extends ChannelDuplexHandler {
    public static final String HANDLER_NAME = "skywindow";

    /** An outbound block action held across a switch, already translated for the sender's frame. */
    private record Held(Object packet, ChannelPromise promise) {
    }

    private static final int HELD_QUEUE_LIMIT = 16;

    private final GeyserSession session;
    private final SkyWindowSession state;
    private final SkyWindowCore core;
    private final GeyserLogger logger;
    private final ChannelHandlerContext managerContext;
    private ChannelHandlerContext ctx;
    // Event-loop confined:
    private final ArrayDeque<Held> heldActions = new ArrayDeque<>(HELD_QUEUE_LIMIT);
    private boolean loggedTransformError;

    public SkyWindowHandler(GeyserSession session, SkyWindowSession state, SkyWindowCore core,
                            GeyserLogger logger, ChannelHandlerContext managerContext) {
        this.session = session;
        this.state = state;
        this.core = core;
        this.logger = logger;
        this.managerContext = managerContext;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        state.channel = ctx.channel();
        state.attached = true;
        // If the login phase already passed while attach() was still retrying, pick up the dimension
        // bounds now; the switch monitor does the rest.
        refreshWindowAndMaybeEvaluate();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        state.attached = false;
        state.channel = null;
        state.offset = 0;
        state.frozen = false;
        heldActions.clear();
        state.resetChunkCache();
    }

    // ---------------------------------------------------------------- inbound

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MinecraftPacket packet)) {
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            handleInbound(ctx, packet);
        } catch (Throwable t) {
            // Never take a connection down over a translation problem: degrade to untranslated.
            logTransformError("inbound", packet, t);
            ctx.fireChannelRead(packet);
        }
    }

    private void handleInbound(ChannelHandlerContext ctx, MinecraftPacket packet) {
        if (packet instanceof ClientboundLoginPacket login) {
            state.playerJavaId = login.getEntityId();
            state.resetChunkCache();
            // Geyser sets the chunk-cache dimension bounds in this very packet's translator, which
            // runs AFTER us: mark dirty so the first chunk packet (guaranteed to follow) re-derives.
            state.windowDirty = true;
            refreshWindowAndMaybeEvaluate();
            ctx.fireChannelRead(login);
            return;
        }
        if (packet instanceof ClientboundRespawnPacket) {
            // A dimension change replaces the world wholesale; drop cached chunks and re-derive.
            state.resetChunkCache();
            state.windowDirty = true;
            refreshWindowAndMaybeEvaluate();
            ctx.fireChannelRead(packet);
            return;
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            if (state.windowDirty) {
                state.windowDirty = false;
                refreshWindowAndMaybeEvaluate();
            }
            // Cache the ORIGINAL real-space payload whenever windowing applies. Windows are re-derived
            // from originals on demand, so a switch accumulates no translation error and the server
            // is never re-queried. Where the real dimension already fits the client window, nothing
            // is cached at all: the whole feature stays provably idle.
            if (windowingApplies()) {
                state.chunkCache.put(chunk);
            }
            int offset = state.offset;
            if (offset != 0 && windowingApplies()) {
                WindowedChunks.Outcome windowed =
                    WindowedChunks.window(chunk, state.window.chunkParams(offset));
                if (windowed.anomaly()) {
                    state.chunkAnomalies.increment();
                } else {
                    state.chunkWindowed.increment();
                }
                watch("IN", "LevelChunkWithLight", "resliced by " + offset
                    + (windowed.anomaly() ? " (normalized)" : ""));
                ctx.fireChannelRead(windowed.packet());
                return;
            }
            ctx.fireChannelRead(chunk);
            return;
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            state.chunkCache.forget(forget.getX(), forget.getZ());
            ctx.fireChannelRead(forget);
            return;
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            if (teleport.getId() == state.playerJavaId && windowingApplies()) {
                state.lastPlayerTeleport = teleport;
                evaluateSwitch(teleport.getPosition().getY());
            }
            // fall through to the generic transform below
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            if (sync.getId() == state.playerJavaId && windowingApplies()) {
                evaluateSwitch(sync.getPosition().getY());
            }
        }

        int offset = state.offset;
        if (offset == 0 || !windowingApplies()) {
            ctx.fireChannelRead(packet);
            return;
        }
        if (InboundYTransforms.handles(packet)) {
            Object translated = InboundYTransforms.apply(packet, offset);
            if (translated != packet) {
                state.inTranslated.increment();
                watch("IN", shortName(packet), "y-" + offset);
            }
            ctx.fireChannelRead(translated);
            return;
        }
        ctx.fireChannelRead(packet);
    }

    // ---------------------------------------------------------------- outbound

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof MinecraftPacket packet)) {
            ctx.write(msg, promise);
            return;
        }
        try {
            int offset = state.offset;
            if (state.frozen) {
                handleFrozenWrite(ctx, packet, promise, offset);
                return;
            }
            if (packet instanceof ServerboundMovePlayerPosPacket pos) {
                evaluateSwitch(pos.getY() + offset);
            } else if (packet instanceof ServerboundMovePlayerPosRotPacket posRot) {
                evaluateSwitch(posRot.getY() + offset);
            }
            if (offset != 0 && windowingApplies()) {
                Object translated = OutboundYTransforms.apply(packet, offset, commandConfig());
                if (translated != packet) {
                    state.outTranslated.increment();
                    watch("OUT", shortName(packet), "y+" + offset);
                }
                ctx.write(translated, promise);
                return;
            }
            ctx.write(packet, promise);
        } catch (Throwable t) {
            logTransformError("outbound", packet, t);
            ctx.write(packet, promise);
        }
    }

    /**
     * While a switch is in flight, the client's position packets still refer to the previous frame;
     * translating them with the new offset would lie. Movement is dropped (a few hundred ms of
     * movement costs nothing, the next packet is right). Block interactions - digging and placing -
     * are instead held and replayed on unfreeze, already translated for the frame the client was in
     * when it sent them, so no interaction is ever silently lost or double-applied.
     */
    private void handleFrozenWrite(ChannelHandlerContext ctx, MinecraftPacket packet, ChannelPromise promise, int offset) {
        if (!OutboundYTransforms.isPositionBearing(packet)) {
            ctx.write(packet, promise);
            return;
        }
        boolean blockInteraction = packet instanceof ServerboundPlayerActionPacket
            || packet instanceof ServerboundUseItemOnPacket;
        if (blockInteraction && core.config().freezeHoldActions && heldActions.size() < HELD_QUEUE_LIMIT) {
            Object translated = OutboundYTransforms.apply(packet, offset, commandConfig());
            heldActions.addLast(new Held(translated, promise));
            state.actionsHeld.increment();
            watch("OUT", shortName(packet), "held across switch");
            return;
        }
        if (packet instanceof ServerboundPlayerActionPacket action
            && (action.getAction() == PlayerAction.START_DESTROY_BLOCK
                || action.getAction() == PlayerAction.ABORT_DESTROY_BLOCK)
            && state.ghostRevertPositions.size() < 8) {
            // A destroy that never reaches the server would leave a locally-broken ghost; mark it for
            // an authoritative block re-send on unfreeze.
            state.ghostRevertPositions.add(action.getPosition());
        }
        state.droppedWhileFrozen.increment();
        promise.trySuccess();
    }

    private void flushHeldActions() {
        ChannelHandlerContext self = ctx;
        if (self == null) {
            heldActions.clear();
            return;
        }
        while (!heldActions.isEmpty()) {
            Held held = heldActions.pollFirst();
            self.write(held.packet(), held.promise()); // below this handler: no re-transform
        }
    }

    // ---------------------------------------------------------------- window switching

    /**
     * Evaluate a switch request. May run on any thread: cheap arithmetic on volatiles, and the switch
     * itself runs on the channel event loop exactly once.
     */
    private void evaluateSwitch(double realY) {
        SkyWindowSession.WindowConfig w = state.window;
        if (w == null || !w.needed() || state.frozen) {
            return;
        }
        int current = state.offset;
        SkyWindowConfig config = core.config();
        if (!WindowRules.shouldSwitch(realY, current, w.clientMinY(), w.clientHeight(),
            config.switchMarginBlocks, w.maxOffset())) {
            return;
        }
        if (System.currentTimeMillis() - state.lastSwitchAtMs < config.switchCooldownMs) {
            return;
        }
        ChannelHandlerContext self = ctx;
        if (self == null || !state.switchQueued.compareAndSet(false, true)) {
            return;
        }
        if (self.channel().eventLoop().inEventLoop()) {
            // Inbound-triggered: switch inline so no packet is forwarded with a stale offset in
            // flight; safe because injected packets enter the pipeline after this handler.
            performSwitch();
        } else {
            self.channel().eventLoop().execute(this::performSwitch);
        }
    }

    /**
     * Runs entirely on the channel event loop: flip the offset, replay cached chunks windowed into the
     * new frame (nearest first), snap the player, and hold stale outbound position packets briefly.
     * Translation and the flip share the event loop, so no packet is ever translated with two offsets.
     */
    private void performSwitch() {
        try {
            state.switchQueued.set(false);
            SkyWindowSession.WindowConfig w = state.window;
            ChannelHandlerContext self = ctx;
            if (w == null || !w.needed() || state.frozen || self == null || !self.channel().isActive()) {
                return;
            }
            if (session.getPlayerEntity() == null || !session.isSpawned()) {
                return;
            }
            double realY = session.getPlayerEntity().position().getY() + state.offset;
            SkyWindowConfig config = core.config();
            int target = WindowRules.targetOffset(realY, w.clientMinY(), w.clientHeight(), w.maxOffset());
            int previous = state.offset;
            if (target == previous) {
                return;
            }
            state.frozen = true;
            state.offset = target;
            state.lastSwitchAtMs = System.currentTimeMillis();
            state.ghostRevertPositions.clear();
            heldActions.clear(); // actions queued against a different switch outcome are invalid

            Vector3f playerPos = session.getPlayerEntity().position();
            for (var chunk : state.chunkCache.nearestFirst((int) playerPos.getX(), (int) playerPos.getZ())) {
                WindowedChunks.Outcome windowed =
                    WindowedChunks.window(chunk, w.chunkParams(target));
                if (windowed.anomaly()) {
                    state.chunkAnomalies.increment();
                } else {
                    state.chunkReplays.increment();
                }
                managerContext.fireChannelRead(windowed.packet());
            }
            injectPlayerSnap(realY, target);

            state.windowSwitches.increment();
            if (config.logSwitches) {
                logger.info("[SkyWindow] " + session.bedrockUsername() + ": height window " + previous + " -> " + target
                    + " (player real Y " + (int) realY + ")");
            }
            long freeze = config.freezeMs;
            self.channel().eventLoop().schedule(() -> {
                state.frozen = false;
                revertDestroyedGhosts();
                flushHeldActions();
            }, freeze, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            state.frozen = false;
            revertDestroyedGhosts();
            flushHeldActions();
            logger.error("[SkyWindow] window switch failed; staying in current window", t);
        }
    }

    private void injectPlayerSnap(double realY, int newOffset) {
        Vector3f p = session.getPlayerEntity().position();
        // X/Z are never windowed; only Y moves. clientY = realY - offset.
        double clientY = realY - newOffset;
        Vector3d targetPos = Vector3d.from(p.getX(), clientY, p.getZ());
        ClientboundTeleportEntityPacket snap;
        ClientboundTeleportEntityPacket last = state.lastPlayerTeleport;
        if (last != null && last.getId() == state.playerJavaId) {
            // Reuse the last real teleport to the player so every flag matches what this server sends;
            // clear relatives so the absolute position we inject is authoritative.
            snap = last.withPosition(targetPos).withRelatives(List.of());
        } else {
            snap = new ClientboundTeleportEntityPacket(
                state.playerJavaId, targetPos, Vector3d.ZERO,
                session.getPlayerEntity().getYaw(), session.getPlayerEntity().getPitch(), List.of(), true);
        }
        managerContext.fireChannelRead(snap);
        watch("IN", "SnapTeleport", "clientY=" + (int) clientY);
    }

    /**
     * Block destruction dropped while frozen never reached the server; push the authoritative block
     * back into Geyser so the client does not keep a locally-broken ghost.
     */
    private void revertDestroyedGhosts() {
        if (state.ghostRevertPositions.isEmpty()) {
            return;
        }
        for (Vector3i windowPos : new java.util.ArrayList<>(state.ghostRevertPositions)) {
            int blockId;
            try {
                blockId = session.getGeyser().getWorldManager().getBlockAt(session, windowPos.getX(), windowPos.getY(), windowPos.getZ());
            } catch (Throwable t) {
                continue;
            }
            managerContext.fireChannelRead(new ClientboundBlockUpdatePacket(new BlockChangeEntry(windowPos, blockId)));
        }
        state.ghostRevertPositions.clear();
    }

    private void refreshWindowAndMaybeEvaluate() {
        state.refreshWindow();
        ChannelHandlerContext self = ctx;
        if (self != null && windowingApplies() && session.getPlayerEntity() != null && session.isSpawned()) {
            evaluateSwitch(session.getPlayerEntity().position().getY() + state.offset);
        }
    }

    private boolean windowingApplies() {
        SkyWindowSession.WindowConfig w = state.window;
        return w != null && w.needed();
    }

    private CommandYRewrite.Config commandConfig() {
        SkyWindowConfig config = core.config();
        return new CommandYRewrite.Config(!config.rewriteCommands.isEmpty(), config.rewriteCommands);
    }

    private void logTransformError(String direction, MinecraftPacket packet, Throwable t) {
        if (loggedTransformError) {
            return;
        }
        loggedTransformError = true;
        logger.error("[SkyWindow] " + direction + " transform failed for "
            + packet.getClass().getSimpleName() + "; this packet type now passes through untranslated", t);
    }

    private void watch(String dir, String what, String note) {
        if (state.watch) {
            try {
                state.pushWatchLine(dir + " " + what + " " + note + " (offset " + state.offset + ")");
            } catch (Throwable ignored) {
                // diagnostics never affect the packet path
            }
        }
    }

    private static String shortName(MinecraftPacket packet) {
        String n = packet.getClass().getSimpleName();
        if (n.endsWith("Packet")) {
            n = n.substring(0, n.length() - 6);
        }
        return n.startsWith("Clientbound") ? n.substring(11) : n.startsWith("Serverbound") ? n.substring(11) : n;
    }
}
