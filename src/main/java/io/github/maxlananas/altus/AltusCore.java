package io.github.maxlananas.altus;

import io.github.maxlananas.altus.pipeline.AltusHandler;
import io.github.maxlananas.altus.session.AltusSession;
import io.github.maxlananas.altus.world.ShiftedWorldManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.level.BedrockDimension;
import org.geysermc.geyser.level.WorldManager;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.NetworkConstants;
import org.geysermc.mcprotocollib.network.session.NetworkSession;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns per-player windowing state and installs everything: the pipeline handler (per session, after Geyser
 * connects downstream) and the shifted world manager (once, at startup). No Bukkit, ProtocolLib or
 * server-plugin dependency - this class only touches Geyser core, mcprotocollib and netty, which the
 * extension classloader resolves from Geyser's own jars (parent-first), so identity always matches.
 */
public final class AltusCore {
    /** What the packet-shape assumptions in docs/ have been verified against; shown by /altus doctor. */
    public static final String TESTED_AGAINST = "Geyser 2.11.2-SNAPSHOT @ 2026-09-08; docs/PACKET-MATRIX.md carries the audit details";

    private final AltusExtension extension;
    private final Map<GeyserSession, AltusSession> states = new ConcurrentHashMap<>();
    private volatile AltusConfig config = AltusConfig.loadDefault();
    /** Derived, allocation-free hot-path view of the config; rewritten only when config reloads. */
    private volatile io.github.maxlananas.altus.translate.CommandYRewrite.Config commandConfig =
        deriveCommandConfig(AltusConfig.loadDefault());
    private volatile boolean worldManagerShifted;
    private volatile boolean running;
    /** Saved reflection handle for the bootstrap field we replaced, for clean restore. */
    private volatile Field worldManagerField;
    private volatile WorldManager originalWorldManager;
    /** Periodic sweep of closed sessions; cancelled by {@link #stop()} so no task outlives the extension. */
    private volatile ScheduledFuture<?> sweeper;

    public AltusCore(AltusExtension extension) {
        this.extension = extension;
    }

    public void start() {
        loadConfig();
        running = config.enabled;
        startSweeper();
        if (!running) {
            extension.logger().info("[Altus] disabled by configuration");
            return;
        }
        installShiftedWorldManager();
        extension.logger().info("[Altus] windowed height support active - players are translated into"
            + " the client's own Y range; see README for the exact limits and guarantees");
    }

    private void loadConfig() {
        Path dir = extension.dataFolder();
        Path file = dir.resolve("config.properties");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dir);
                migrateLegacyConfig(dir, file);
            }
            if (!Files.exists(file)) {
                Files.writeString(file, AltusConfig.sampleFileContents());
            }
            config = AltusConfig.load(file);
            commandConfig = deriveCommandConfig(config);
        } catch (IOException e) {
            extension.logger().warning("[Altus] could not read " + file + ", using defaults: " + e.getMessage());
            config = AltusConfig.loadDefault();
        }
        commandConfig = deriveCommandConfig(config);
        for (String warning : config.warnings) {
            extension.logger().warning("[Altus] config: " + warning);
        }
    }

    /**
     * Copies the 1.0.x configuration ({@code extensions/skywindow/config.properties}) into the new data folder
     * the first time Altus starts. The old file is left in place, so rolling back the jar keeps working.
     */
    private void migrateLegacyConfig(Path dir, Path file) throws IOException {
        Path legacy = dir.resolveSibling("skywindow").resolve("config.properties");
        if (Files.isRegularFile(legacy)) {
            Files.copy(legacy, file);
            extension.logger().info("[Altus] migrated configuration from " + legacy + " to " + file
                + " (the old file is kept)");
        }
    }

    /**
     * Config reload without disturbing live sessions. Handlers read configuration through this core on every
     * packet; the chunk-cache budget is re-applied to every tracked session; enabling re-attaches the pipeline
     * for players already connected; disabling detaches every pipeline but keeps the session states so that a
     * later re-enable does not depend on a reconnect.
     */
    public void reload() {
        loadConfig();
        for (AltusSession state : states.values()) {
            state.chunkCache.resize(config.chunkCacheMaxChunks, config.chunkCacheMaxMegabytes * 1024L * 1024L);
        }
        running = config.enabled;
        if (running) {
            installShiftedWorldManager();
            attachAll();
        } else {
            detachAll();
            unwrapWorldManager();
        }
    }

    public void stop() {
        running = false;
        ScheduledFuture<?> task = sweeper;
        sweeper = null;
        if (task != null) {
            task.cancel(false);
        }
        detachAll();
        states.clear();
        unwrapWorldManager();
    }

    public boolean running() {
        return running;
    }

    /**
     * Periodic safety net: sessions that died between initialize and join never fire
     * SessionDisconnectEvent (Geyser only fires it once auth/client data exist), which would leak
     * their state entries on servers with abandoned login attempts. Sweeping closed sessions keeps
     * the map bounded regardless of event-path gaps.
     */
    private void startSweeper() {
        if (sweeper != null) {
            return;
        }
        try {
            sweeper = GeyserImpl.getInstance().getScheduledThread().scheduleWithFixedDelay(() -> {
                try {
                    for (Map.Entry<GeyserSession, AltusSession> e : states.entrySet()) {
                        if (e.getKey().isClosed()) {
                            AltusSession orphan = states.remove(e.getKey());
                            if (orphan != null) {
                                orphan.resetChunkCache();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // sweep never must fail the scheduler
                }
            }, 60, 60, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            // executor unavailable: states still cleaned by the disconnect event path
        }
    }

    /** Per-type quarantine registry (deterministic transform failures), for /altus doctor. */
    private final Map<String, String> quarantinedTypes = new ConcurrentHashMap<>();

    public void reportQuarantinedType(String typeName, String reason) {
        quarantinedTypes.put(typeName, reason);
    }

    public Map<String, String> quarantinedTypes() {
        return quarantinedTypes;
    }

    /** Removes every pipeline handler. Session states are kept; the handler's removal resets the offset. */
    private void detachAll() {
        for (Map.Entry<GeyserSession, AltusSession> e : states.entrySet()) {
            AltusSession state = e.getValue();
            state.attached = false;
            var channel = state.channel;
            if (channel != null && channel.isActive()) {
                channel.eventLoop().execute(() -> {
                    if (channel.pipeline().get(AltusHandler.HANDLER_NAME) != null) {
                        channel.pipeline().remove(AltusHandler.HANDLER_NAME);
                    }
                });
            }
        }
    }

    /** Re-attaches the pipeline for every open session that is currently tracked. */
    private void attachAll() {
        for (Map.Entry<GeyserSession, AltusSession> e : states.entrySet()) {
            GeyserSession session = e.getKey();
            AltusSession state = e.getValue();
            if (session.isClosed() || state.attached) {
                continue;
            }
            state.refreshWindow();
            tryAttach(session, state, 0);
        }
    }

    // ------------------------------------------------------------------ config & state access

    public AltusConfig config() {
        return config;
    }

    public io.github.maxlananas.altus.translate.CommandYRewrite.Config commandConfig() {
        return commandConfig;
    }

    private static io.github.maxlananas.altus.translate.CommandYRewrite.Config deriveCommandConfig(
        AltusConfig config) {
        return new io.github.maxlananas.altus.translate.CommandYRewrite.Config(
            !config.rewriteCommands.isEmpty(), config.rewriteCommands);
    }

    public AltusSession state(GeyserSession session) {
        return states.get(session);
    }

    /** Manual exact-window move for {@code /altus window <realY>} (builder tooling). */
    public boolean forceWindow(GeyserSession session, double realY) {
        AltusSession state = states.get(session);
        var channel = state == null ? null : state.channel;
        if (channel == null || !channel.isActive()) {
            return false;
        }
        var handler = channel.pipeline().get(AltusHandler.HANDLER_NAME);
        if (handler instanceof AltusHandler h) {
            h.forceWindow(realY);
            return true;
        }
        return false;
    }

    public int currentOffset(GeyserSession session) {
        AltusSession state = states.get(session);
        return state == null ? 0 : state.offset;
    }

    public boolean worldManagerShifted() {
        return worldManagerShifted;
    }

    // ------------------------------------------------------------------ session lifecycle

    public void onSessionInitialized(GeyserConnection connection) {
        GeyserSession session = cast(connection);
        if (session == null || session.isClosed()) {
            return;
        }
        // State is created even while disabled, so that enabling the extension later can attach it.
        AltusConfig cfg = config;
        states.computeIfAbsent(session, s -> new AltusSession(
            () -> recomputeWindow(s), cfg.chunkCacheMaxChunks, cfg.chunkCacheMaxMegabytes * 1024L * 1024L));
    }

    public void onSessionJoined(GeyserConnection connection) {
        GeyserSession session = cast(connection);
        AltusSession state = session == null ? null : states.get(session);
        if (state == null) {
            return;
        }
        state.refreshWindow();
        tryAttach(session, state, 0);
    }

    public void onSessionDisconnected(GeyserConnection connection) {
        GeyserSession session = cast(connection);
        if (session == null) {
            return;
        }
        AltusSession state = states.remove(session);
        if (state != null) {
            state.resetChunkCache();
        }
    }

    private void tryAttach(GeyserSession session, AltusSession state, int attempt) {
        if (!running || state.attached || session.isClosed()) {
            return;
        }
        long delayMs = attempt < 10 ? 100L : 500L;
        GeyserImpl.getInstance().getScheduledThread().schedule(
            () -> installOrRetry(session, state, attempt), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void installOrRetry(GeyserSession session, AltusSession state, int attempt) {
        if (!running || state.attached || session.isClosed()) {
            return;
        }
        if (attempt > 100) {
            extension.logger().warning("[Altus] could not attach to "
                + LogSanitizer.playerName(session.bedrockUsername())
                + " (downstream pipeline not found); this player stays in real space - no windowing"
                + " and no collision-read protection for them");
            return;
        }
        Channel channel = downstreamChannel(session);
        if (channel == null || !channel.isActive() || !channel.isOpen()) {
            tryAttach(session, state, attempt + 1);
            return;
        }
        channel.eventLoop().execute(() -> {
            if (!installHandler(session, state, channel)) {
                tryAttach(session, state, attempt + 1);
            }
        });
    }

    /** Runs on the channel event loop. */
    private boolean installHandler(GeyserSession session, AltusSession state, Channel channel) {
        var pipeline = channel.pipeline();
        if (pipeline.get(AltusHandler.HANDLER_NAME) != null) {
            return true;
        }
        if (pipeline.get(NetworkConstants.CODEC_NAME) == null || pipeline.get(NetworkConstants.MANAGER_NAME) == null) {
            return false;
        }
        ChannelHandlerContext managerContext = pipeline.context(NetworkConstants.MANAGER_NAME);
        pipeline.addAfter(NetworkConstants.CODEC_NAME, AltusHandler.HANDLER_NAME,
            new AltusHandler(session, state, this, GeyserImpl.getInstance().getLogger(), managerContext));
        return true;
    }

    private static Channel downstreamChannel(GeyserSession session) {
        var downstream = session.getDownstream();
        if (downstream == null) {
            return null;
        }
        var clientSession = downstream.getSession();
        return clientSession instanceof NetworkSession networkSession ? networkSession.getChannel() : null;
    }

    // ------------------------------------------------------------------ window snapshot

    private AltusSession.WindowConfig recomputeWindow(GeyserSession session) {
        try {
            // ChunkCache exposes SECTION units; the window math works in block units end to end.
            int javaMinSection = session.getChunkCache().getChunkMinY();
            int javaSectionCount = session.getChunkCache().getChunkHeightY();
            int javaMinY = javaMinSection * 16;
            int javaMaxY = javaMinY + javaSectionCount * 16;
            BedrockDimension dimension = session.getBedrockDimension();
            return AltusSession.WindowConfig.of(javaMinY, javaMaxY,
                dimension.minY(), dimension.height(), config.maxOffsetBlocks);
        } catch (Throwable t) {
            extension.logger().warning("[Altus] could not read dimension bounds for "
                + LogSanitizer.playerName(session.bedrockUsername()) + ": " + t + " (keeping the previous window)");
            return null;
        }
    }

    // ------------------------------------------------------------------ world manager swap

    /**
     * Geyser keeps its {@link WorldManager} on the platform bootstrap, usually in a private field, so
     * this is the extension's single guarded reflection point. If the field cannot be found or written,
     * Altus still removes block/placement desync (packet layer) but keeps the documented limitation that
     * session-aware direct world reads (collision corrections) may still disagree on platforms where
     * the manager is consulted; the log says so explicitly.
     */
    private void installShiftedWorldManager() {
        try {
            Object bootstrap = GeyserImpl.getInstance().getBootstrap();
            if (bootstrap == null) {
                throw new IllegalStateException("no bootstrap");
            }
            Field field = findWorldManagerField(bootstrap.getClass());
            field.setAccessible(true);
            Object current = field.get(bootstrap);
            if (current instanceof ShiftedWorldManager shifted) {
                // Already wrapped by an earlier enable cycle (enabled -> reload -> still enabled). Re-wrapping
                // would double-shift, so the existing wrap is adopted. Its delegate is the real original, which
                // is what a later disable must restore; leaving the wrapper in place would shift reads forever.
                this.worldManagerField = field;
                this.originalWorldManager = shifted.delegate();
                worldManagerShifted = true;
                return;
            }
            if (!(current instanceof WorldManager manager)) {
                throw new IllegalStateException("unexpected world manager: " + current);
            }
            field.set(bootstrap, new ShiftedWorldManager(manager, this));
            this.worldManagerField = field;
            this.originalWorldManager = manager;
            worldManagerShifted = true;
            extension.logger().info("[Altus] world manager wrapped: session-aware direct reads now follow the window");
        } catch (Throwable t) {
            worldManagerShifted = false;
            extension.logger().warning("[Altus] could not wrap the platform world manager (" + t
                + "); high-altitude movement corrections from Geyser-side direct world reads may still occur -"
                + " run /altus doctor after updating Geyser");
        }
    }

    /**
     * Match by type, not name: platforms declare this with platform-specific names and subclasses
     * (Geyser-Spigot uses {@code private GeyserSpigotWorldManager geyserWorldManager}). Final fields
     * are skipped: we do not fight immutability that Geyser relies on elsewhere.
     */
    private void unwrapWorldManager() {
        Field field = worldManagerField;
        WorldManager original = originalWorldManager;
        if (field == null || original == null) {
            return;
        }
        try {
            Object bootstrap = GeyserImpl.getInstance().getBootstrap();
            if (bootstrap != null && field.get(bootstrap) instanceof ShiftedWorldManager) {
                field.set(bootstrap, original);
            }
        } catch (Throwable ignored) {
            // shutdown path; a leftover wrapper disappears with the process anyway
        }
        worldManagerShifted = false;
    }

    private static Field findWorldManagerField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (WorldManager.class.isAssignableFrom(f.getType())
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && !java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                    return f;
                }
            }
        }
        throw new IllegalStateException("no settable WorldManager field found on " + type.getName());
    }

    private static GeyserSession cast(GeyserConnection connection) {
        return connection instanceof GeyserSession session ? session : null;
    }
}
