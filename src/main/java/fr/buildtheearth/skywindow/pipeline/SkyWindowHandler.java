package fr.buildtheearth.skywindow.pipeline;

import fr.buildtheearth.skywindow.SkyWindowConfig;
import fr.buildtheearth.skywindow.SkyWindowCore;
import fr.buildtheearth.skywindow.session.ChunkCache;
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
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Single translation choke point for one Bedrock player. Installed on Geyser's Java-side pipeline directly
 * after mcprotocollib's {@code codec} handler, so inbound packets arrive decoded in real space and outbound
 * packets are seen right before serialization in client space. All absolute-Y translation for the session
 * happens here, once.
 *
 * <p>Inbound runs on the channel event loop. Outbound {@code write} can run on any thread (Geyser's tick
 * loop included). Every window switch, freeze transition and outbound write that could interact with a
 * freeze runs under {@link #freezeLock}, so a held packet is never overtaken by a later packet on the same
 * stream, and the unfreeze flush is atomic with respect to new writes.</p>
 *
 * <p>Freeze protocol. A switch flips the offset, replays the cached chunks in the new frame, and (for
 * switches not caused by a server teleport) snaps the client. Until the freeze ends, outbound packets are
 * classified by {@link OutboundYTransforms#frozenDisposition}: non-positional packets pass, movement and
 * block actions are held, and anything else is dropped. Held packets are replayed in the frame that
 * {@link FrameResolver} finds plausible, or dropped if no frame is. The anchor is the last known real Y,
 * set by the switch trigger and advanced by each resolved movement. Docs: docs/ARCHITECTURE.md.</p>
 *
 * <p>Failure policy. A transform exception quarantines its packet class for the session. While the
 * offset is non-zero, packets of a quarantined class are dropped (fail closed), because forwarding them
 * untranslated would place them in the wrong frame. At offset zero they pass, which is already the right
 * frame. Nothing throws out of a packet path.</p>
 */
public final class SkyWindowHandler extends ChannelDuplexHandler {
    public static final String HANDLER_NAME = "skywindow";

    /** Combined cap for the held queue: 400 ms of freeze cannot legitimately produce more. */
    private static final int HELD_QUEUE_LIMIT = 48;

    private final GeyserSession session;
    private final SkyWindowSession state;
    private final SkyWindowCore core;
    private final GeyserLogger logger;
    private final ChannelHandlerContext managerContext;
    private final HeldQueue held;
    /** Serializes freeze transitions with outbound writes; see the class javadoc. */
    private final Object freezeLock = new Object();
    // Event-loop confined:
    private ChannelHandlerContext ctx;
    private volatile Set<Class<?>> quarantined;
    private boolean loggedTransformError;

    public SkyWindowHandler(GeyserSession session, SkyWindowSession state, SkyWindowCore core,
                            GeyserLogger logger, ChannelHandlerContext managerContext) {
        this.session = java.util.Objects.requireNonNull(session);
        this.state = java.util.Objects.requireNonNull(state);
        this.core = java.util.Objects.requireNonNull(core);
        this.logger = java.util.Objects.requireNonNull(logger);
        this.managerContext = java.util.Objects.requireNonNull(managerContext);
        // Built here, not in the field initializer: the overflow lambda must capture a fully assigned state.
        this.held = new HeldQueue(HELD_QUEUE_LIMIT, state.heldOverflow::increment);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        state.channel = ctx.channel();
        state.attached = true;
        state.degraded = false;
        // If the login phase already passed while attach() was still retrying, pick up the dimension
        // bounds now; the position packets that follow drive the rest.
        state.refreshWindow();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        state.attached = false;
        state.channel = null;
        resetTransition();
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
            var q = quarantined;
            if (q != null && q.contains(packet.getClass())) {
                forwardUntranslatedOrDrop(ctx, packet);
                return;
            }
            handleInbound(ctx, packet);
        } catch (Throwable t) {
            quarantine("inbound", packet, t);
            forwardUntranslatedOrDrop(ctx, packet);
        }
    }

    private void handleInbound(ChannelHandlerContext ctx, MinecraftPacket packet) {
        if (packet instanceof ClientboundLoginPacket login) {
            state.playerJavaId = login.getEntityId();
            startNewWorld();
            ctx.fireChannelRead(login);
            return;
        }
        if (packet instanceof ClientboundRespawnPacket) {
            // A dimension change replaces the world wholesale; drop cached chunks and re-derive.
            startNewWorld();
            ctx.fireChannelRead(packet);
            return;
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            handleChunk(ctx, chunk);
            return;
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            state.chunkCache.forget(forget.getX(), forget.getZ());
            ctx.fireChannelRead(forget);
            return;
        }
        if (packet instanceof ClientboundBlockUpdatePacket
            || packet instanceof ClientboundSectionBlocksUpdatePacket
            || packet instanceof ClientboundBlockEntityDataPacket) {
            journal(packet);
        }
        if (packet instanceof ClientboundPlayerPositionPacket position) {
            refreshWindowIfDirty();
            inboundAuthoritative(ctx, packet, position.getPosition().getY(),
                position.getRelatives().contains(PositionElement.Y));
            return;
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport && teleport.getId() == state.playerJavaId) {
            inboundAuthoritative(ctx, packet, teleport.getPosition().getY(),
                teleport.getRelatives().contains(PositionElement.Y));
            return;
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync && sync.getId() == state.playerJavaId) {
            inboundAuthoritative(ctx, packet, sync.getPosition().getY(), false);
            return;
        }
        translateInbound(ctx, packet);
    }

    private void handleChunk(ChannelHandlerContext ctx, ClientboundLevelChunkWithLightPacket chunk) {
        refreshWindowIfDirty();
        // Cache the ORIGINAL real-space payload whenever windowing applies, so a switch never re-queries the
        // server and never accumulates translation error. Where the real dimension already fits the client
        // window, nothing is cached and the feature stays idle.
        SkyWindowSession.WindowConfig w = state.window;
        boolean applies = w != null && w.needed();
        if (applies && !state.chunkCache.put(chunk)) {
            state.chunkUncacheable.increment();
            if (state.watch) {
                watch("IN", "LevelChunkWithLight", "larger than the cache budget; not replayable on switch");
            }
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
    }

    /**
     * Server-authoritative positions (player teleports, position syncs). The packet itself is the
     * client-visible teleport, so no snap is injected: the switch happens first, then the packet is
     * translated in the new frame.
     */
    private void inboundAuthoritative(ChannelHandlerContext ctx, MinecraftPacket packet, double rawY, boolean relativeY) {
        if (appliesToPlayer()) {
            double realY = relativeY ? state.lastSeenRealY + rawY : rawY;
            synchronized (freezeLock) {
                state.lastSeenRealY = realY;
            }
            requestSwitch(realY, true);
        }
        translateInbound(ctx, packet);
    }

    private void translateInbound(ChannelHandlerContext ctx, MinecraftPacket packet) {
        int offset = state.offset;
        if (offset == 0 || !appliesToPlayer()) {
            ctx.fireChannelRead(packet);
            return;
        }
        Object translated = InboundYTransforms.apply(packet, offset);
        if (translated != packet) {
            state.inTranslated.increment();
            if (state.watch) {
                watch("IN", shortName(packet), "y-" + offset);
            }
        }
        ctx.fireChannelRead(translated);
    }

    /** Records block and block-entity changes in the chunk journal, in real coordinates, before translation. */
    private void journal(MinecraftPacket packet) {
        SkyWindowSession.WindowConfig w = state.window;
        if (w == null || !w.needed()) {
            return;
        }
        ChunkCache.Record worst = ChunkCache.Record.NOT_CACHED;
        if (packet instanceof ClientboundBlockUpdatePacket p) {
            worst = worse(worst, state.chunkCache.recordBlock(p.getEntry()));
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket p) {
            for (BlockChangeEntry entry : p.getEntries()) {
                worst = worse(worst, state.chunkCache.recordBlock(entry));
            }
        } else if (packet instanceof ClientboundBlockEntityDataPacket p) {
            worst = worse(worst, state.chunkCache.recordBlockEntity(p));
        }
        if (worst == ChunkCache.Record.EVICTED_JOURNAL_OVERFLOW) {
            state.journalOverflows.increment();
            if (state.watch) {
                watch("IN", shortName(packet), "chunk journal overflow; chunk evicted");
            }
        }
    }

    private static ChunkCache.Record worse(ChunkCache.Record a, ChunkCache.Record b) {
        return b == ChunkCache.Record.EVICTED_JOURNAL_OVERFLOW || a == ChunkCache.Record.EVICTED_JOURNAL_OVERFLOW
            ? ChunkCache.Record.EVICTED_JOURNAL_OVERFLOW
            : b == ChunkCache.Record.RECORDED ? ChunkCache.Record.RECORDED : a;
    }

    // ---------------------------------------------------------------- outbound

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof MinecraftPacket packet)) {
            ctx.write(msg, promise);
            return;
        }
        try {
            var q = quarantined;
            if (q != null && q.contains(packet.getClass())) {
                if (passesUntranslated()) {
                    ctx.write(packet, promise);
                } else {
                    dropForFailure(packet, promise);
                }
                return;
            }
            synchronized (freezeLock) {
                if (state.frozen) {
                    writeFrozen(ctx, packet, promise);
                } else {
                    writeLive(ctx, packet, promise);
                }
            }
        } catch (Throwable t) {
            quarantine("outbound", packet, t);
            if (passesUntranslated()) {
                ctx.write(packet, promise);
            } else {
                dropForFailure(packet, promise);
            }
        }
    }

    private void writeLive(ChannelHandlerContext ctx, MinecraftPacket packet, ChannelPromise promise) {
        // Movement is the hottest outbound type (20-30/s per player); it is handled inline so the switch
        // evaluation reuses the exact sum that is translated.
        if (packet instanceof ServerboundMovePlayerPosPacket pos) {
            writeMovement(ctx, pos.getY(), promise, pos, false);
            return;
        }
        if (packet instanceof ServerboundMovePlayerPosRotPacket posRot) {
            writeMovement(ctx, posRot.getY(), promise, posRot, true);
            return;
        }
        int offset = state.offset;
        if (offset != 0 && appliesToPlayer()) {
            Object translated = OutboundYTransforms.apply(packet, offset, core.commandConfig());
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
    }

    /** Translates a movement packet and evaluates a switch from its real Y. */
    private void writeMovement(ChannelHandlerContext ctx, double clientY, ChannelPromise promise,
                               MinecraftPacket packet, boolean withRotation) {
        if (!appliesToPlayer()) {
            ctx.write(packet, promise);
            return;
        }
        int offset = state.offset;
        double realY = clientY + offset;
        state.lastSeenRealY = realY;
        // May switch inline. The packet is still written: its real Y was computed in the frame it was
        // sent in, so it is correct whether or not the switch happened.
        requestSwitch(realY, false);
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
     * While a switch is in flight, the client may still be in the previous frame, so the packet's frame is
     * unknown. Each class of packet is handled as {@link OutboundYTransforms#frozenDisposition} says.
     */
    private void writeFrozen(ChannelHandlerContext ctx, MinecraftPacket packet, ChannelPromise promise) {
        SkyWindowConfig config = core.config();
        switch (OutboundYTransforms.frozenDisposition(packet)) {
            case TRANSMIT -> ctx.write(packet, promise);
            case HOLD_MOVEMENT -> holdOrDrop(packet, promise, false, config.freezeHoldMovement);
            case HOLD_BLOCK_ACTION -> holdOrDrop(packet, promise, true, config.freezeHoldActions);
            case DROP -> dropFrozen(packet, promise);
        }
    }

    private void holdOrDrop(MinecraftPacket packet, ChannelPromise promise, boolean blockAction, boolean enabled) {
        if (enabled && held.offer(new HeldQueue.Entry(packet, promise, blockAction))) {
            state.actionsHeld.increment();
            if (state.watch) {
                watch("OUT", shortName(packet), "held across switch");
            }
            return;
        }
        dropFrozen(packet, promise);
    }

    private void dropFrozen(MinecraftPacket packet, ChannelPromise promise) {
        state.droppedWhileFrozen.increment();
        recordGhost(packet);
        if (state.watch) {
            watch("OUT", shortName(packet), "dropped across switch");
        }
        promise.trySuccess();
    }

    private void dropForFailure(MinecraftPacket packet, ChannelPromise promise) {
        state.transformFailures.increment();
        if (state.watch) {
            watch("OUT", shortName(packet), "dropped: transform failure (fail closed)");
        }
        promise.trySuccess();
    }

    /**
     * A dropped destroy would leave the client showing a block the server still has. The position is kept
     * in the client's raw coordinates; both possible frames are re-checked when the freeze ends.
     */
    private void recordGhost(MinecraftPacket packet) {
        if (packet instanceof ServerboundPlayerActionPacket action
            && (action.getAction() == PlayerAction.START_DIGGING
                || action.getAction() == PlayerAction.CANCEL_DIGGING
                || action.getAction() == PlayerAction.FINISH_DIGGING)
            && state.ghostRevertPositions.size() < SkyWindowSession.GHOST_REVERT_LIMIT) {
            state.ghostRevertPositions.add(action.getPosition());
        }
    }

    // ---------------------------------------------------------------- window switching

    /**
     * Requests a switch if the real Y at {@code realY} calls for one. Runs on any thread. The switch itself
     * runs once, on the channel event loop, under the freeze lock.
     *
     * @param authoritative true when {@code realY} comes from a server teleport; the cooldown is bypassed,
     *                      because the client is already at that position
     */
    private void requestSwitch(double realY, boolean authoritative) {
        if (!core.running()) {
            return;
        }
        SkyWindowSession.WindowConfig w = state.window;
        if (w == null || !w.needed() || state.frozen) {
            return;
        }
        SkyWindowConfig config = core.config();
        if (!WindowRules.shouldSwitch(realY, state.offset, w.clientMinY(), w.clientHeight(),
            config.switchMarginBlocks, w.maxOffset())) {
            return;
        }
        if (!authoritative && System.currentTimeMillis() - state.lastSwitchAtMs
            < config.switchCooldownMs * Math.max(1, state.switchBackoff)) {
            return;
        }
        ChannelHandlerContext self = ctx;
        if (self == null) {
            return;
        }
        long generation = state.worldGeneration;
        if (self.channel().eventLoop().inEventLoop()) {
            performSwitch(realY, !authoritative, generation);
            return;
        }
        if (!state.switchQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            self.channel().eventLoop().execute(() -> performSwitch(realY, !authoritative, generation));
        } catch (Throwable t) {
            state.switchQueued.set(false); // channel closing; never leave the latch stuck
        }
    }

    /**
     * Runs on the channel event loop, under the freeze lock: flips the offset, replays the cached chunks
     * (journal included) in the new frame, optionally snaps the client, and schedules the unfreeze.
     *
     * @param realY      the player's real Y that triggered the switch
     * @param injectSnap whether to snap the client; false when the triggering packet is itself the teleport
     * @param generation world generation the request was made in; a stale request is ignored
     */
    private void performSwitch(double realY, boolean injectSnap, long generation) {
        synchronized (freezeLock) {
            state.switchQueued.set(false);
            SkyWindowSession.WindowConfig w = state.window;
            ChannelHandlerContext self = ctx;
            if (self == null || !self.channel().isActive() || !core.running() || w == null || !w.needed()
                || state.frozen || generation != state.worldGeneration || session.getPlayerEntity() == null) {
                return;
            }
            SkyWindowConfig config = core.config();
            int target = WindowRules.targetOffset(realY, w.clientMinY(), w.clientHeight(), w.maxOffset());
            int previous = state.offset;
            if (target == previous) {
                return;
            }
            long now = System.currentTimeMillis();
            long sinceLast = now - state.lastSwitchAtMs;
            state.switchBackoff = sinceLast < config.switchCooldownMs * 4 ? Math.min(8, state.switchBackoff * 2) : 1;

            long epoch = ++state.freezeEpoch;
            state.frozen = true;
            state.frozenFrameOffset = previous;
            state.offset = target;
            state.lastSeenRealY = realY;
            state.switchStartNanos = System.nanoTime();
            state.lastSwitchAtMs = now;
            held.clearAndComplete();
            state.ghostRevertPositions.clear();
            state.windowSwitches.increment();

            replayWorld(w, target);
            if (injectSnap && session.isSpawned()) {
                injectPlayerSnap(realY, target);
            }
            if (config.logSwitches) {
                logger.info("[SkyWindow] " + core.safeName(session.bedrockUsername()) + ": height window "
                    + previous + " -> " + target + " (player real Y " + (int) realY + ")");
            }
            if (config.announceSwitches) {
                announceSwitch(previous, target);
            }
            scheduleUnfreeze(self, epoch, config.freezeMs);
        }
    }

    /** Operator-initiated exact-window move ({@code /skywindow window <realY>}); same machinery, given target. */
    public void forceWindow(double realY) {
        ChannelHandlerContext self = ctx;
        if (self == null) {
            return;
        }
        long generation = state.worldGeneration;
        if (self.channel().eventLoop().inEventLoop()) {
            performSwitch(realY, true, generation);
        } else {
            self.channel().eventLoop().execute(() -> performSwitch(realY, true, generation));
        }
    }

    private void scheduleUnfreeze(ChannelHandlerContext self, long epoch, long freezeMs) {
        try {
            self.channel().eventLoop().schedule(() -> unfreeze(epoch), freezeMs, TimeUnit.MILLISECONDS);
        } catch (Throwable schedulingFailed) {
            // Executor shutting down: unwind the freeze inline so the player is never stranded.
            unfreeze(epoch);
        }
    }

    private void unfreeze(long epoch) {
        synchronized (freezeLock) {
            if (!state.frozen || epoch != state.freezeEpoch) {
                return;
            }
            state.lastFreezeNanos = System.nanoTime() - state.switchStartNanos;
            state.freezeTotalNanos.add(state.lastFreezeNanos);
            state.freezeCount.increment();
            ChannelHandlerContext self = ctx;
            try {
                if (self == null || !self.channel().isActive()) {
                    held.clearAndComplete();
                    state.ghostRevertPositions.clear();
                } else {
                    flushHeld();
                    revertDestroyedGhosts();
                }
            } catch (Throwable t) {
                logger.error("[SkyWindow] unfreeze failed; held packets were dropped", t);
                held.clearAndComplete();
                state.ghostRevertPositions.clear();
            } finally {
                state.frozen = false;
            }
        }
        // A teleport or switch request that arrived during the freeze was deferred; evaluate it now.
        if (appliesToPlayer()) {
            requestSwitch(state.lastSeenRealY, false);
        }
    }

    /**
     * Replays held packets in the order they were sent. Each movement and block action is placed in the
     * frame that {@link FrameResolver} finds plausible relative to the anchor, which advances as movements
     * resolve. Anything without a unique plausible frame is dropped, never guessed.
     */
    private void flushHeld() {
        ChannelHandlerContext self = ctx;
        int previous = state.frozenFrameOffset;
        int current = state.offset;
        double anchor = state.lastSeenRealY;
        double bound = FrameResolver.travelBound(System.nanoTime() - state.switchStartNanos);
        HeldQueue.Entry entry;
        while ((entry = held.poll()) != null) {
            Object raw = entry.packet();
            if (!self.channel().isActive()) {
                entry.promise().trySuccess();
                continue;
            }
            try {
                Object out;
                if (entry.blockAction()) {
                    Vector3i pos = OutboundYTransforms.blockPosition(raw);
                    FrameResolver.Frame frame = pos == null
                        ? FrameResolver.Frame.NONE
                        : FrameResolver.blockAction(anchor, pos.getY(), previous, current);
                    if (frame == FrameResolver.Frame.NONE) {
                        dropFrozen((MinecraftPacket) raw, entry.promise());
                        continue;
                    }
                    out = OutboundYTransforms.apply(raw, frame == FrameResolver.Frame.PREVIOUS ? previous : current,
                        core.commandConfig());
                } else {
                    double clientY = OutboundYTransforms.movementY(raw);
                    FrameResolver.Frame frame = Double.isNaN(clientY)
                        ? FrameResolver.Frame.NONE
                        : FrameResolver.movement(anchor, clientY, previous, current, bound);
                    if (frame == FrameResolver.Frame.NONE) {
                        dropFrozen((MinecraftPacket) raw, entry.promise());
                        continue;
                    }
                    int off = frame == FrameResolver.Frame.PREVIOUS ? previous : current;
                    out = OutboundYTransforms.apply(raw, off, core.commandConfig());
                    anchor = clientY + off;
                }
                self.write(out, entry.promise()); // below this handler: no re-transform
            } catch (Throwable t) {
                quarantine("replay", (MinecraftPacket) raw, t);
                dropFrozen((MinecraftPacket) raw, entry.promise());
            }
        }
        state.lastSeenRealY = anchor;
    }

    /**
     * Re-sends the authoritative state of blocks whose destroy was dropped. Each candidate frame is checked:
     * the position is re-read through the (shifted) world manager at the current client frame, and only
     * positions inside the visible window are sent.
     */
    private void revertDestroyedGhosts() {
        if (state.ghostRevertPositions.isEmpty()) {
            return;
        }
        SkyWindowSession.WindowConfig w = state.window;
        int previous = state.frozenFrameOffset;
        int current = state.offset;
        for (Vector3i raw : new ArrayList<>(state.ghostRevertPositions)) {
            if (w == null) {
                break;
            }
            for (int frame : new int[] {previous, current}) {
                int clientY = raw.getY() + frame - current;
                if (!w.clientYVisible(clientY)) {
                    continue;
                }
                int blockId;
                try {
                    // The wrapped world manager adds the current offset, so this reads the real block.
                    blockId = session.getGeyser().getWorldManager().getBlockAt(session, raw.getX(), clientY, raw.getZ());
                } catch (Throwable t) {
                    continue;
                }
                managerContext.fireChannelRead(new ClientboundBlockUpdatePacket(
                    new BlockChangeEntry(Vector3i.from(raw.getX(), clientY, raw.getZ()), blockId)));
            }
        }
        state.ghostRevertPositions.clear();
    }

    /**
     * Replays every cached chunk in the new frame, nearest first, each followed by the block and
     * block-entity changes journaled since it arrived, so that edits made before the switch survive it.
     */
    private void replayWorld(SkyWindowSession.WindowConfig w, int target) {
        Vector3f p = session.getPlayerEntity().position();
        for (ChunkCache.Entry entry : state.chunkCache.nearestFirst((int) p.getX(), (int) p.getZ())) {
            try {
                WindowedChunks.Outcome windowed = WindowedChunks.window(entry.packet(), w.chunkParams(target));
                if (windowed.anomaly()) {
                    state.chunkAnomalies.increment();
                } else {
                    state.chunkReplays.increment();
                }
                managerContext.fireChannelRead(windowed.packet());
                for (BlockChangeEntry change : entry.blockChanges()) {
                    Vector3i pos = change.getPosition();
                    int clientY = pos.getY() - target;
                    if (w.clientYVisible(clientY)) {
                        managerContext.fireChannelRead(new ClientboundBlockUpdatePacket(
                            new BlockChangeEntry(Vector3i.from(pos.getX(), clientY, pos.getZ()), change.getBlock())));
                    }
                }
                for (ClientboundBlockEntityDataPacket update : entry.blockEntityChanges()) {
                    Vector3i pos = update.getPosition();
                    int clientY = pos.getY() - target;
                    if (w.clientYVisible(clientY)) {
                        managerContext.fireChannelRead(update.withPosition(Vector3i.from(pos.getX(), clientY, pos.getZ())));
                    }
                }
            } catch (Throwable t) {
                // One chunk failing to replay must not abort the switch for the others.
                state.chunkAnomalies.increment();
                logger.error("[SkyWindow] chunk replay failed for chunk " + entry.packet().getX() + ","
                    + entry.packet().getZ(), t);
            }
        }
    }

    private void injectPlayerSnap(double realY, int newOffset) {
        var entity = session.getPlayerEntity();
        Vector3f p = entity.position();
        // X/Z are never windowed; only Y moves. clientY = realY - offset.
        double clientY = realY - newOffset;
        Vector3d targetPos = Vector3d.from(p.getX(), clientY, p.getZ());
        // ClientboundTeleportEntityPacket for the player id is what Geyser maps to a TELEPORT MovePlayer for the
        // Bedrock client. It is not a server teleport, so it carries no teleport id and nothing is acknowledged.
        managerContext.fireChannelRead(new ClientboundTeleportEntityPacket(
            state.playerJavaId, targetPos, Vector3d.ZERO, entity.getYaw(), entity.getPitch(), List.of(), true));
        if (state.watch) {
            watch("IN", "SnapTeleport", "clientY=" + (int) clientY);
        }
    }

    private void refreshWindowIfDirty() {
        if (state.windowDirty) {
            state.windowDirty = false;
            state.refreshWindow();
        }
    }

    /**
     * New world (login or respawn): the old frame, cached chunks and held packets belong to a world that no
     * longer exists. The offset returns to real space; the next position packet selects a window again.
     */
    private void startNewWorld() {
        state.resetChunkCache();
        state.worldGeneration++;
        state.windowDirty = true;
        resetTransition();
    }

    private void resetTransition() {
        synchronized (freezeLock) {
            state.frozen = false;
            state.freezeEpoch++;
            state.offset = 0;
            state.frozenFrameOffset = 0;
            state.switchQueued.set(false);
            held.clearAndComplete(); // never leave Geyser's write futures hanging
            state.ghostRevertPositions.clear();
        }
    }

    private boolean appliesToPlayer() {
        SkyWindowSession.WindowConfig w = state.window;
        return w != null && w.needed();
    }

    /** Whether a packet may pass untranslated: true only when the client is in real space already. */
    private boolean passesUntranslated() {
        return state.offset == 0 || !appliesToPlayer();
    }

    private void forwardUntranslatedOrDrop(ChannelHandlerContext ctx, MinecraftPacket packet) {
        if (passesUntranslated()) {
            ctx.fireChannelRead(packet);
        } else {
            state.transformFailures.increment();
        }
    }

    private void announceSwitch(int from, int to) {
        try {
            session.sendMessage("§b[SkyWindow] §7fenêtre de hauteur recalée: "
                + (to - from > 0 ? "↑" : "↓") + " " + Math.abs(to - from) + " blocs (automatique)");
        } catch (Throwable ignored) {
            // cosmetics never affect the packet path
        }
    }

    private void quarantine(String direction, MinecraftPacket packet, Throwable t) {
        // Event-loop or freeze-lock confined set; the first failure per class is remembered so a
        // deterministic protocol breakage costs one exception per session per type, not one per packet.
        if (quarantined == null) {
            quarantined = ConcurrentHashMap.newKeySet();
        }
        if (quarantined.add(packet.getClass())) {
            core.reportQuarantinedType(packet.getClass().getSimpleName(), direction + ": " + t);
            if (!loggedTransformError) {
                loggedTransformError = true;
                logger.error("[SkyWindow] " + direction + " transform failed for "
                    + packet.getClass().getSimpleName() + "; packets of this type are dropped while the"
                    + " height window is active (fail closed)", t);
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
