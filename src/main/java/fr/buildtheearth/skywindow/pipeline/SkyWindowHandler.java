package fr.buildtheearth.skywindow.pipeline;

import fr.buildtheearth.skywindow.SkyWindowConfig;
import fr.buildtheearth.skywindow.SkyWindowCore;
import fr.buildtheearth.skywindow.session.SkyWindowSession;
import fr.buildtheearth.skywindow.translate.InboundYTransforms;
import fr.buildtheearth.skywindow.translate.OutboundYTransforms;
import fr.buildtheearth.skywindow.translate.WindowedChunks;
import fr.buildtheearth.skywindow.window.WindowRules;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Single translation choke point for one Bedrock player. Installed on Geyser's Java-side pipeline
 * directly after mcprotocollib's {@code codec} handler, so inbound packets are seen freshly decoded
 * (server/real space) and outbound packets are seen right before serialization (client/window space).
 * All absolute-Y translation for the session happens here, once - Geyser's translators, the Bedrock
 * protocol and the Java server all keep speaking their normal coordinate systems, and the offset never
 * passes through more than one layer.
 *
 * <p>Injection of synthetic packets (chunk replays, switch snaps, ghost reverts) is done with
 * {@code ctx.fireChannelRead(...)} from this handler's own context: that lands in the next inbound
 * handler (mcprotocollib's {@code manager}, i.e. the {@code NetworkSession} that dispatches to
 * Geyser's packet listeners) exactly as if this handler had forwarded it - already in window space,
 * without re-entering its own transform. (Firing from the manager's own context would skip the
 * manager entirely and end at the netty tail, where the message is silently discarded.)</p>
 *
 * <p>Threading: inbound and the whole window switch run on the channel event loop, so anything not
 * explicitly volatile here is confined to it. Outbound {@code write} may also run on the Geyser tick
 * loop (and the Bedrock-side netty thread); there, state is only read (volatiles), the held queues are
 * concurrent ({@link HeldQueue}), and decisions that mutate event-loop state are bounced to it.</p>
 *
 * <p>The freeze protocol and its frame-resolution rules for held packets are documented in
 * docs/ARCHITECTURE.md ("Window switching") - the short version: packets a client sent while it still
 * lived in the previous frame must be translated with the PREVIOUS offset; the switch therefore
 * remembers {@code frozenFrameOffset} and held block-position packets are only replayed when their
 * position is physically plausible (within reach of the player's real position) under that frame,
 * then the new one, and are dropped with an authoritative revert if neither fits.</p>
 */
public final class SkyWindowHandler extends ChannelDuplexHandler {
    public static final String HANDLER_NAME = "skywindow";

    /**
     * Cap for the held queue: 400 ms of freeze plus a hostile 100 ms freeze setting at high
     * packet rates must fit (movement ~30/s, dig/place bursts, the odd sign/command edit).
     */
    private static final int HELD_QUEUE_LIMIT = 96;
    /**
     * Max |held block position - player real position| for a block-position packet to be replayed.
     * Bedrock reach is ~7 blocks; the discriminator only needs to be below half of the smallest legal
     * offset distance (16). Both frame hypotheses cannot satisfy this simultaneously when dOffset >= 16,
     * which is an invariant of the windowing design (section-aligned offsets).
     */
    private static final int HELD_ACTION_REACH_BLOCKS = 7;

    private final GeyserSession session;
    private final SkyWindowSession state;
    private final SkyWindowCore core;
    private final GeyserLogger logger;
    private final HeldQueue held;
    // Event-loop confined:
    private ChannelHandlerContext ctx;
    private volatile Set<Class<?>> quarantined; // lazily allocated; classes whose transform threw once are skipped
    private boolean loggedTransformError;

    public SkyWindowHandler(GeyserSession session, SkyWindowSession state, SkyWindowCore core,
                            GeyserLogger logger) {
        this.session = java.util.Objects.requireNonNull(session);
        this.state = java.util.Objects.requireNonNull(state);
        this.core = java.util.Objects.requireNonNull(core);
        this.logger = java.util.Objects.requireNonNull(logger);
        // Built here, not in the field initializer: the overflow lambda must capture a fully
        // assigned `state` (the compiler is right to reject a forward reference in an initializer).
        this.held = new HeldQueue(HELD_QUEUE_LIMIT, state.heldOverflow::increment);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        state.channel = ctx.channel();
        state.attached = true;
        state.degraded = false;
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
        held.clearAndComplete(); // never leave Geyser's write futures hanging
        state.resetChunkCache();
    }

    // ---------------------------------------------------------------- inbound

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MinecraftPacket packet)) {
            ctx.fireChannelRead(msg);
            return;
        }
        var q = quarantined;
        if (q != null && q.contains(packet.getClass())) {
            ctx.fireChannelRead(packet);
            return;
        }
        try {
            handleInbound(ctx, packet);
        } catch (Throwable t) {
            // Never take a connection down over a translation problem: degrade this packet type to
            // untranslated passthrough for the rest of the session and surface it in the doctor.
            quarantine("inbound", packet, t);
            ctx.fireChannelRead(packet);
        }
    }

    private void handleInbound(ChannelHandlerContext ctx, MinecraftPacket packet) {
        if (packet instanceof ClientboundLoginPacket login) {
            state.playerJavaId = login.getEntityId();
            state.resetChunkCache();
            state.worldGeneration++;
            // Geyser sets the chunk-cache dimension bounds in this very packet's translator, which
            // runs AFTER us: mark dirty so the first chunk packet (guaranteed to follow) re-derives.
            state.windowDirty = true;
            refreshWindowAndMaybeEvaluate();
            // Falls through to the generic transform: PlayerSpawnInfo.lastDeathPos is absolute Y.
            int offset = state.offset;
            if (offset == 0 || !appliesToPlayer()) {
                ctx.fireChannelRead(login);
                return;
            }
            Object translated = InboundYTransforms.apply(login, offset, core.commandConfig());
            if (translated != login) {
                state.inTranslated.increment();
                if (state.watch) {
                    watch("IN", "Login", "y-" + offset + " (death pos)");
                }
            }
            ctx.fireChannelRead(translated);
            return;
        }
        if (packet instanceof ClientboundRespawnPacket respawn) {
            // A dimension change replaces the world wholesale; drop cached chunks and re-derive.
            state.resetChunkCache();
            state.worldGeneration++;
            state.windowDirty = true;
            refreshWindowAndMaybeEvaluate();
            int offset = state.offset;
            if (offset == 0 || !appliesToPlayer()) {
                ctx.fireChannelRead(respawn);
                return;
            }
                Object translated = InboundYTransforms.apply(respawn, offset, core.commandConfig());
            if (translated != respawn) {
                state.inTranslated.increment();
                if (state.watch) {
                    watch("IN", "Respawn", "y-" + offset + " (death pos)");
                }
            }
            ctx.fireChannelRead(translated);
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
            SkyWindowSession.WindowConfig w = state.window;
            boolean applies = w != null && w.needed();
            if (applies) {
                state.chunkCache.put(chunk);
            }
            int offset = state.offset;
            if (offset != 0 && applies) {
                WindowedChunks.Outcome windowed = WindowedChunks.window(chunk, w.chunkParams(offset));
                if (windowed.anomaly()) {
                    state.chunkAnomalies.increment();
                } else {
                    state.chunkWindowed.increment();
                }
                if (state.watch) {
                    watch("IN", "LevelChunkWithLight", "resliced by " + offset
                        + (windowed.anomaly() ? " (normalized)" : ""));
                }
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
        if (packet instanceof ClientboundCommandsPacket commandsPacket) {
            // The server's Brigadier tree (every command of every plugin, with typed arguments).
            // Indexed here so all later command translation knows exactly which tokens are
            // coordinates - this is the universal command-shape oracle, see CommandTreeIndex.
            state.commandTree = fr.buildtheearth.skywindow.translate.CommandTreeIndex.build(commandsPacket);
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            if (teleport.getId() == state.playerJavaId && appliesToPlayer()) {
                state.lastPlayerTeleport = teleport;
                evaluateSwitch(realYOf(teleport.getPosition().getY(),
                    InboundYTransforms.isRelativeY(teleport.getRelatives())));
            }
            // fall through to the generic transform below (which itself honors relative Y)
        }
        if (packet instanceof ClientboundPlayerPositionPacket position && appliesToPlayer()) {
            // The player-teleport family (its "id" is a teleport id, not an entity id - every such
            // packet is about our own player): monitor it (this is how /tp, warps and portals move
            // the player) and let the generic transform shift its absolute Y.
            evaluateSwitch(realYOf(position.getPosition().getY(),
                InboundYTransforms.isRelativeY(position.getRelatives())));
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            if (sync.getId() == state.playerJavaId && appliesToPlayer()) {
                evaluateSwitch(realYOf(sync.getPosition().getY(), false));
            }
        }

        int offset = state.offset;
        if (offset == 0 || !appliesToPlayer()) {
            ctx.fireChannelRead(packet);
            return;
        }
        Object translated = InboundYTransforms.apply(packet, offset, core.commandConfig(), state.commandTree);
        if (translated != packet) {
            state.inTranslated.increment();
            if (state.watch) {
                watch("IN", shortName(packet), "y-" + offset);
            }
        }
        ctx.fireChannelRead(translated);
    }

    /** Real Y of a client-side/server-side position field, relative flags respected. */
    private double realYOf(double packetY, boolean relativeY) {
        if (!relativeY) {
            return packetY + state.offset;
        }
        double currentReal = state.lastSeenRealY;
        if (currentReal == 0 && session.getPlayerEntity() != null) {
            currentReal = session.getPlayerEntity().position().getY() + state.offset;
        }
        return currentReal + packetY;
    }

    // ---------------------------------------------------------------- outbound

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof MinecraftPacket packet)) {
            ctx.write(msg, promise);
            return;
        }
        var q = quarantined;
        if (q != null && q.contains(packet.getClass())) {
            ctx.write(packet, promise);
            return;
        }
        try {
            int offset = state.offset;
            if (state.frozen) {
                handleFrozenWrite(ctx, packet, promise, offset);
                return;
            }
            // Movement is the hottest outbound packet type by an order of magnitude (20-30/s per
            // player). Handle it fully inline: one type check total, and the switch evaluation reuses
            // the exact sum used for translation.
            if (packet instanceof ServerboundMovePlayerPosPacket pos) {
                writeMovement(ctx, pos.getY(), offset, promise, pos, false);
                return;
            }
            if (packet instanceof ServerboundMovePlayerPosRotPacket posRot) {
                writeMovement(ctx, posRot.getY(), offset, promise, posRot, true);
                return;
            }
            if (offset != 0 && appliesToPlayer()) {
                Object translated = OutboundYTransforms.apply(packet, offset, core.commandConfig(), state.commandTree);
                if (translated != packet) {
                    state.outTranslated.increment();
                    if (state.watch) {
                        watch("OUT", shortName(packet), "y+" + offset);
                    }
                }
                ctx.write(translated, promise);
                return;
            }
            ctx.write(packet, promise);
        } catch (Throwable t) {
            quarantine("outbound", packet, t);
            ctx.write(packet, promise);
        }
    }

    /** Writes a movement packet: translate + switch evaluation in one pass (pos vs posRot flag picks the withY shape). */
    private void writeMovement(ChannelHandlerContext ctx, double clientY, int offset,
                               ChannelPromise promise, Object packet, boolean withRotation) {
        if (!appliesToPlayer()) {
            ctx.write(packet, promise);
            return;
        }
        double realY = clientY + offset;
        state.lastSeenRealY = realY;
        evaluateSwitch(realY);
        if (offset == 0) {
            ctx.write(packet, promise);
            return;
        }
        Object translated = withRotation
            ? ((ServerboundMovePlayerPosRotPacket) packet).withY(realY)
            : ((ServerboundMovePlayerPosPacket) packet).withY(realY);
        state.outTranslated.increment();
        if (state.watch) {
            watch("OUT", withRotation ? "MovePlayerPosRot" : "MovePlayerPos", "y+" + offset);
        }
        ctx.write(translated, promise);
    }

    /**
     * While a switch is in flight, the client has not necessarily received the snap teleport yet:
     * packets arriving now are mostly still written in the PREVIOUS frame, so translating them with
     * the new offset would lie about the player's real position. Every position-bearing packet is
     * therefore held and replayed on unfreeze in a frame-aware way ({@link #flushHeld}) instead of
     * being written mis-translated: movement in the frame it was sent in (fall/elytra continuity),
     * block-position packets only when the frame hypothesis is physically plausible (reach check),
     * commands in the sender's frame. Anything unresolvable or overflowing the queue falls back to
     * the drop path with an authoritative ghost revert for dig/place.
     */
    private void handleFrozenWrite(ChannelHandlerContext ctx, MinecraftPacket packet, ChannelPromise promise, int offset) {
        if (!OutboundYTransforms.isPositionBearing(packet)) {
            // Truly Y-free (rotations, swings, container clicks, accept-teleport, ...): forward raw.
            ctx.write(packet, promise);
            return;
        }
        boolean blockPositioned = OutboundYTransforms.isBlockAction(packet)
            || OutboundYTransforms.blockPosition(packet) != null;
        boolean movementLike = !blockPositioned && !OutboundYTransforms.isCommandPacket(packet);
        boolean hold = (blockPositioned && core.config().freezeHoldActions)
            || (movementLike && core.config().freezeHoldMovement)
            || OutboundYTransforms.isCommandPacket(packet);
        state.lastSeenRealY = clientFrameRealY(packet, state.frozenFrameOffset, state.lastSeenRealY);
        if (hold && held.offer(new HeldQueue.Entry(packet, promise, blockPositioned))) {
            state.actionsHeld.increment();
            if (state.watch) {
                watch("OUT", shortName(packet), "held across switch");
            }
            return;
        }
        if (blockPositioned) {
            recordGhost(packet);
        }
        state.droppedWhileFrozen.increment();
        promise.trySuccess();
    }

    /**
     * Remembers the authoritative re-send targets for a dropped dig/place: the dig position, or for a
     * placement both the clicked block and the position a block would appear at. Stored in REAL space
     * (the frame the packet was sent in, resolved now) so the unfreeze re-send survives any offset
     * bookkeeping in between. Capped; best-effort cosmetics only.
     */
    private void recordGhost(MinecraftPacket packet) {
        if (packet instanceof ServerboundPlayerActionPacket action
            && (action.getAction() == PlayerAction.START_DIGGING
                || action.getAction() == PlayerAction.FINISH_DIGGING
                || action.getAction() == PlayerAction.CANCEL_DIGGING)) {
            addGhost(action.getPosition(), state.frozenFrameOffset);
        } else if (packet instanceof ServerboundUseItemOnPacket use) {
            addGhost(use.getPosition(), state.frozenFrameOffset);
            addGhost(faceTarget(use.getPosition(), use.getFace()), state.frozenFrameOffset);
        }
    }

    private void addGhost(Vector3i windowPos, int frameOffset) {
        if (state.ghostRevertPositions.size() >= SkyWindowSession.GHOST_REVERT_LIMIT) {
            return;
        }
        state.ghostRevertPositions.add(Vector3i.from(windowPos.getX(), windowPos.getY() + frameOffset, windowPos.getZ()));
    }

    private static Vector3i faceTarget(Vector3i pos, Direction face) {
        return switch (face) {
            case DOWN -> Vector3i.from(pos.getX(), pos.getY() - 1, pos.getZ());
            case UP -> Vector3i.from(pos.getX(), pos.getY() + 1, pos.getZ());
            case NORTH -> Vector3i.from(pos.getX(), pos.getY(), pos.getZ() - 1);
            case SOUTH -> Vector3i.from(pos.getX(), pos.getY(), pos.getZ() + 1);
            case WEST -> Vector3i.from(pos.getX() - 1, pos.getY(), pos.getZ());
            case EAST -> Vector3i.from(pos.getX() + 1, pos.getY(), pos.getZ());
        };
    }

    /** The player's physical Y as implied by a packet the client sent in frame {@code frameOffset}; double-guarded. */
    private double clientFrameRealY(MinecraftPacket packet, int frameOffset, double fallback) {
        return switch (packet) {
            case ServerboundMovePlayerPosPacket p -> p.getY() + frameOffset;
            case ServerboundMovePlayerPosRotPacket p -> p.getY() + frameOffset;
            case ServerboundMoveVehiclePacket p -> p.getPosition().getY() + frameOffset;
            default -> fallback;
        };
    }

    private void flushHeld() {
        ChannelHandlerContext self = ctx;
        int offsetNow = state.offset;
        int frozenFrame = state.frozenFrameOffset;
        double playerRealY = state.lastSeenRealY;
        HeldQueue.Entry entry;
        while ((entry = held.poll()) != null) {
            if (self == null || !self.channel().isActive()) {
                entry.promise().trySuccess();
                continue;
            }
            try {
                Object out = entry.packet();
                if (out instanceof MinecraftPacket packet) {
                    if (entry.blockAction()) {
                        // Frame resolution: translate under each candidate frame and replay only the
                        // interpretation whose block position is within physical reach of the player.
                        // (Identity translations - e.g. frozen frame offset 0 - are legitimate.)
                        Object oldFrame = OutboundYTransforms.apply(packet, frozenFrame, core.commandConfig(), state.commandTree);
                        if (plausible(oldFrame, playerRealY)) {
                            out = oldFrame; // sent before the snap took effect: pre-switch frame
                        } else {
                            Object newFrame = offsetNow == frozenFrame ? oldFrame
                                : OutboundYTransforms.apply(packet, offsetNow, core.commandConfig(), state.commandTree);
                            if (plausible(newFrame, playerRealY)) {
                                out = newFrame; // sent after the snap: current frame
                            } else {
                                state.droppedWhileFrozen.increment();
                                recordGhost(packet);
                                entry.promise().trySuccess();
                                continue; // unresolvable frame: drop, never guess
                            }
                        }
                    } else {
                        // Movement/vehicle/commands: replay with the frame they were sent in;
                        // staleness is bounded by the freeze and self-corrected by the next packet.
                        out = OutboundYTransforms.apply(packet, frozenFrame, core.commandConfig(), state.commandTree);
                    }
                }
                self.write(out, entry.promise()); // below this handler: no re-transform
            } catch (Throwable t) {
                quarantine("replay", (MinecraftPacket) entry.packet(), t);
                entry.promise().trySuccess();
            }
        }
    }

    /** @return true when the translated packet's primary block position is within reach of the player. */
    private static boolean plausible(Object translatedPacket, double playerRealY) {
        Vector3i pos = OutboundYTransforms.blockPosition(translatedPacket);
        if (pos == null) {
            return false;
        }
        return Math.abs(pos.getY() + 0.5 - playerRealY) <= HELD_ACTION_REACH_BLOCKS;
    }

    // ---------------------------------------------------------------- window switching

    /**
     * Evaluate a switch request. May run on any thread: cheap arithmetic on volatiles, and the switch
     * itself runs on the channel event loop exactly once.
     */
    private void evaluateSwitch(double realY) {
        if (!core.running()) {
            return;
        }
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
        long cooldown = config.switchCooldownMs * Math.max(1, state.switchBackoff);
        if (System.currentTimeMillis() - state.lastSwitchAtMs < cooldown) {
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
            try {
                self.channel().eventLoop().execute(this::performSwitch);
            } catch (Throwable t) {
                state.switchQueued.set(false); // channel closing; never leave the latch stuck
            }
        }
    }

    /**
     * Runs entirely on the channel event loop: flip the offset, replay cached chunks windowed into the
     * new frame (nearest first), snap the player, and hold stale outbound position packets briefly.
     * Translation and the flip share the event loop, so no packet is ever translated with two offsets
     * concurrently; packets that raced in while the client still had the old frame are replayed on
     * unfreeze through the frame-resolution rules in {@link #flushHeld}.
     */
    private void performSwitch() {
        try {
            state.switchQueued.set(false);
            SkyWindowSession.WindowConfig w = state.window;
            ChannelHandlerContext self = ctx;
            if (w == null || !w.needed() || state.frozen || self == null || !self.channel().isActive()
                || !core.running()) {
                return;
            }
            if (session.getPlayerEntity() == null || !session.isSpawned()) {
                return;
            }
            double realY = session.getPlayerEntity().position().getY() + state.offset;
            if (state.forcedRealY >= 0) {
                realY = state.forcedRealY;
                state.forcedRealY = -1;
            }
            SkyWindowConfig config = core.config();
            int target = WindowRules.targetOffset(realY, w.clientMinY(), w.clientHeight(), w.maxOffset());
            int previous = state.offset;
            if (target == previous) {
                return;
            }
            long sinceLast = System.currentTimeMillis() - state.lastSwitchAtMs;
            state.switchBackoff = sinceLast < config.switchCooldownMs * 4 ? Math.min(8, state.switchBackoff * 2) : 1;
            state.frozen = true;
            state.frozenFrameOffset = previous;
            state.offset = target;
            state.lastSwitchAtMs = System.currentTimeMillis();
            state.lastSeenRealY = realY;
            long worldGen = state.worldGeneration;
            held.clearAndComplete(); // queued against an abandoned frame outcome: complete, drop
            state.ghostRevertPositions.clear();

            Vector3f playerPos = session.getPlayerEntity().position();
            for (var chunk : state.chunkCache.nearestFirst((int) playerPos.getX(), (int) playerPos.getZ())) {
                WindowedChunks.Outcome windowed = WindowedChunks.window(chunk, w.chunkParams(target));
                if (windowed.anomaly()) {
                    state.chunkAnomalies.increment();
                } else {
                    state.chunkReplays.increment();
                }
                inject(windowed.packet());
            }
            injectPlayerSnap(realY, target);

            state.windowSwitches.increment();
            if (config.logSwitches) {
                logger.info("[SkyWindow] " + core.safeName(session.bedrockUsername()) + ": height window "
                    + previous + " -> " + target + " (player real Y " + (int) realY + ")");
            }
            if (config.announceSwitches) {
                announceSwitch(previous, target);
            }
            long freezeStart = System.nanoTime();
            try {
                self.channel().eventLoop().schedule(() -> {
                    state.lastFreezeNanos = System.nanoTime() - freezeStart;
                    state.freezeTotalNanos.add(state.lastFreezeNanos);
                    state.freezeCount.increment();
                    if (!self.channel().isActive() || state.worldGeneration != worldGen) {
                        // Player left or the world replaced everything the ghosts/held entries refer to.
                        held.clearAndComplete();
                        state.ghostRevertPositions.clear();
                        state.frozen = false;
                        return;
                    }
                    state.frozen = false;
                    revertDestroyedGhosts();
                    flushHeld();
                    if (state.forcedRealY >= 0) {
                        // /skywindow window arrived during the freeze: run it now through the normal
                        // machinery rather than leaving the operator hanging.
                        performSwitch();
                    }
                }, config.freezeMs, TimeUnit.MILLISECONDS);
            } catch (Throwable schedulingFailed) {
                // Executor shutting down: unwind the freeze inline so the player is never stranded.
                state.frozen = false;
                revertDestroyedGhosts();
                flushHeld();
            }
        } catch (Throwable t) {
            state.frozen = false;
            revertDestroyedGhosts();
            flushHeld();
            logger.error("[SkyWindow] window switch failed; staying in current window", t);
        }
    }

    /**
     * Operator-initiated exact-window move ({@code /skywindow window <realY>}): same machinery, given
     * target. Unlike 3.x-era forcing this never assigns an offset behind the pipeline's back - it goes
     * through {@link #performSwitch} (freeze, replay, snap, backoff, derived cap). A request arriving
     * mid-freeze waits for the freeze to unwind; one arriving inside the backoff window is scheduled
     * at its expiry, so spamming the command throttles like automatic switching.
     */
    public void forceWindow(double realY) {
        ChannelHandlerContext self = ctx;
        if (self == null) {
            return;
        }
        state.forcedRealY = realY;
        try {
            if (self.channel().eventLoop().inEventLoop()) {
                performForcedSwitch();
            } else {
                self.channel().eventLoop().execute(this::performForcedSwitch);
            }
        } catch (Throwable ignored) {
            // channel closing; the request simply does not land
        }
    }

    /** Event-loop confined forced-switch entry: applies the same anti-storm throttle as automatic switches. */
    private void performForcedSwitch() {
        state.switchQueued.set(false); // any retry we scheduled owns the latch only until here
        if (state.forcedRealY < 0 || state.frozen) {
            return; // consumed elsewhere, or the freeze's unwinding will re-invoke us
        }
        ChannelHandlerContext self = ctx;
        if (self == null || !self.channel().isActive()) {
            return;
        }
        SkyWindowConfig config = core.config();
        long cooldown = config.switchCooldownMs * Math.max(1, state.switchBackoff);
        long sinceLast = System.currentTimeMillis() - state.lastSwitchAtMs;
        if (sinceLast < cooldown) {
            if (state.switchQueued.compareAndSet(false, true)) {
                try {
                    self.channel().eventLoop().schedule(this::performForcedSwitch, cooldown - sinceLast,
                        TimeUnit.MILLISECONDS);
                } catch (Throwable t) {
                    state.switchQueued.set(false);
                }
            }
            return;
        }
        performSwitch();
    }

    private void announceSwitch(int from, int to) {
        try {
            session.sendMessage("§b[SkyWindow] §7fenêtre de hauteur recalée: "
                + (to - from > 0 ? "↑" : "↓") + " " + Math.abs(to - from) + " blocs (automatique)");
        } catch (Throwable ignored) {
            // cosmetics never affect the packet path
        }
    }

    /**
     * Injects an already-windowed packet downstream of this handler: it enters Geyser's packet
     * dispatch (the {@code manager} handler) exactly as a forwarded server packet would, without
     * re-entering this handler's transform.
     */
    private void inject(MinecraftPacket packet) {
        ChannelHandlerContext self = ctx;
        if (self != null) {
            self.fireChannelRead(packet);
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
        inject(snap);
        if (state.watch) {
            watch("IN", "SnapTeleport", "clientY=" + (int) clientY);
        }
    }

    /**
     * Block destruction/placement dropped while frozen never reached the server; push the
     * authoritative block back into Geyser so the client does not keep a locally-broken ghost.
     * Stored positions are REAL space; they are re-projected into the client's current frame here.
     */
    private void revertDestroyedGhosts() {
        if (state.ghostRevertPositions.isEmpty()) {
            return;
        }
        int offset = state.offset;
        for (Vector3i realPos : new java.util.ArrayList<>(state.ghostRevertPositions)) {
            Vector3i windowPos = Vector3i.from(realPos.getX(), realPos.getY() - offset, realPos.getZ());
            int blockId;
            try {
                // The read goes through the (session-aware, shifted) world manager with the window
                // position and comes back as the same real block; the re-send is injected past our
                // own transform so the client gets the right block at the position it sees.
                blockId = session.getGeyser().getWorldManager().getBlockAt(session, windowPos.getX(), windowPos.getY(), windowPos.getZ());
            } catch (Throwable t) {
                continue;
            }
            inject(new ClientboundBlockUpdatePacket(new BlockChangeEntry(windowPos, blockId)));
        }
        state.ghostRevertPositions.clear();
    }

    private void refreshWindowAndMaybeEvaluate() {
        state.refreshWindow();
        ChannelHandlerContext self = ctx;
        if (self != null && appliesToPlayer() && session.getPlayerEntity() != null && session.isSpawned()) {
            if (core.config().logSwitches) {
                SkyWindowSession.WindowConfig w = state.window;
                logger.info("[SkyWindow] " + core.safeName(session.bedrockUsername()) + ": window client["
                    + w.clientMinY() + ".." + (w.clientMinY() + w.clientHeight()) + ") java[" + w.javaMinY()
                    + ".." + w.javaMaxY() + ") cap=" + w.maxOffset() + " needed=" + w.needed());
            }
            evaluateSwitch(session.getPlayerEntity().position().getY() + state.offset);
        }
    }

    private boolean appliesToPlayer() {
        SkyWindowSession.WindowConfig w = state.window;
        return w != null && w.needed();
    }

    private void quarantine(String direction, MinecraftPacket packet, Throwable t) {
        // Event-loop confined set; first failure per class is remembered so deterministic protocol
        // breakage costs one exception per session per type instead of one per packet.
        if (quarantined == null) {
            quarantined = ConcurrentHashMap.newKeySet();
        }
        if (quarantined.add(packet.getClass())) {
            core.reportQuarantinedType(packet.getClass().getSimpleName(), direction + ": " + t);
            if (!loggedTransformError) {
                loggedTransformError = true;
                logger.error("[SkyWindow] " + direction + " transform failed for "
                    + packet.getClass().getSimpleName() + "; this packet type now passes through untranslated", t);
            }
        }
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
