package fr.buildtheearth.skywindow;

import fr.buildtheearth.skywindow.pipeline.SkyWindowHandler;
import fr.buildtheearth.skywindow.session.SkyWindowSession;
import fr.buildtheearth.skywindow.world.ShiftedWorldManager;
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

/**
 * Owns per-player windowing state and installs everything: the pipeline handler (per session, after Geyser
 * connects downstream) and the shifted world manager (once, at startup). No Bukkit, ProtocolLib or
 * server-plugin dependency - this class only touches Geyser core, mcprotocollib and netty, which the
 * extension classloader resolves from Geyser's own jars (parent-first), so identity always matches.
 */
public final class SkyWindowCore {
    private final SkyWindowExtension extension;
    private final Map<GeyserSession, SkyWindowSession> states = new ConcurrentHashMap<>();
    private volatile SkyWindowConfig config = SkyWindowConfig.loadDefault();
    private volatile boolean worldManagerShifted;
    private volatile boolean running;
    /** Saved reflection handle for the bootstrap field we replaced, for clean restore. */
    private volatile Field worldManagerField;
    private volatile WorldManager originalWorldManager;

    public SkyWindowCore(SkyWindowExtension extension) {
        this.extension = extension;
    }

    public void start() {
        loadConfig();
        running = config.enabled;
        if (!running) {
            extension.logger().info("[SkyWindow] disabled by configuration");
            return;
        }
        installShiftedWorldManager();
        extension.logger().info("[SkyWindow] windowed height support active - players are translated into"
            + " the client's own Y range; see README for the exact limits and guarantees");
    }

    private void loadConfig() {
        Path dir = extension.dataFolder();
        Path file = dir.resolve("config.properties");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dir);
                Files.writeString(file, SkyWindowConfig.sampleFileContents());
            }
            config = SkyWindowConfig.load(file);
        } catch (IOException e) {
            extension.logger().warning("[SkyWindow] could not read " + file + ", using defaults: " + e.getMessage());
            config = SkyWindowConfig.loadDefault();
        }
    }

    /** Config reload without disturbing live sessions: handlers read config through this core. */
    public void reload() {
        loadConfig();
        running = config.enabled;
        if (running) {
            installShiftedWorldManager();
        } else {
            detachAll();
            unwrapWorldManager();
        }
    }

    public void stop() {
        running = false;
        detachAll();
        unwrapWorldManager();
    }

    private void detachAll() {
        for (SkyWindowSession state : states.values()) {
            state.frozen = false;
            state.offset = 0;
            state.resetChunkCache();
            var channel = state.channel;
            if (channel != null && channel.isActive()) {
                channel.eventLoop().execute(() -> {
                    if (channel.pipeline().get(SkyWindowHandler.HANDLER_NAME) != null) {
                        channel.pipeline().remove(SkyWindowHandler.HANDLER_NAME);
                    }
                });
            }
        }
        states.clear();
    }

    // ------------------------------------------------------------------ config & state access

    public SkyWindowConfig config() {
        return config;
    }

    public SkyWindowSession state(GeyserSession session) {
        return states.get(session);
    }

    public int currentOffset(GeyserSession session) {
        SkyWindowSession state = states.get(session);
        return state == null ? 0 : state.offset;
    }

    public boolean worldManagerShifted() {
        return worldManagerShifted;
    }

    // ------------------------------------------------------------------ session lifecycle

    public void onSessionInitialized(GeyserConnection connection) {
        GeyserSession session = cast(connection);
        if (session == null || !running) {
            return;
        }
        SkyWindowConfig cfg = config;
        states.computeIfAbsent(session, s -> new SkyWindowSession(
            () -> recomputeWindow(s), cfg.chunkCacheMaxChunks, cfg.chunkCacheMaxMegabytes * 1024L * 1024L));
    }

    public void onSessionJoined(GeyserConnection connection) {
        GeyserSession session = cast(connection);
        SkyWindowSession state = session == null ? null : states.get(session);
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
        SkyWindowSession state = states.remove(session);
        if (state != null) {
            state.resetChunkCache();
        }
    }

    private void tryAttach(GeyserSession session, SkyWindowSession state, int attempt) {
        if (!running || state.attached || session.isClosed()) {
            return;
        }
        long delayMs = attempt < 10 ? 100L : 500L;
        GeyserImpl.getInstance().getScheduledThread().schedule(
            () -> installOrRetry(session, state, attempt), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void installOrRetry(GeyserSession session, SkyWindowSession state, int attempt) {
        if (!running || state.attached || session.isClosed()) {
            return;
        }
        if (attempt > 100) {
            extension.logger().warning("[SkyWindow] could not attach to " + session.bedrockUsername()
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
    private boolean installHandler(GeyserSession session, SkyWindowSession state, Channel channel) {
        var pipeline = channel.pipeline();
        if (pipeline.get(SkyWindowHandler.HANDLER_NAME) != null) {
            return true;
        }
        if (pipeline.get(NetworkConstants.CODEC_NAME) == null || pipeline.get(NetworkConstants.MANAGER_NAME) == null) {
            return false;
        }
        ChannelHandlerContext managerContext = pipeline.context(NetworkConstants.MANAGER_NAME);
        pipeline.addAfter(NetworkConstants.CODEC_NAME, SkyWindowHandler.HANDLER_NAME,
            new SkyWindowHandler(session, state, this, GeyserImpl.getInstance().getLogger(), managerContext));
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

    private SkyWindowSession.WindowConfig recomputeWindow(GeyserSession session) {
        try {
            // ChunkCache exposes SECTION units; the window math works in block units end to end.
            int javaMinSection = session.getChunkCache().getChunkMinY();
            int javaSectionCount = session.getChunkCache().getChunkHeightY();
            int javaMinY = javaMinSection * 16;
            int javaMaxY = javaMinY + javaSectionCount * 16;
            BedrockDimension dimension = session.getBedrockDimension();
            return SkyWindowSession.WindowConfig.of(javaMinY, javaMaxY,
                dimension.minY(), dimension.height(), config.maxOffsetBlocks);
        } catch (Throwable t) {
            extension.logger().warning("[SkyWindow] could not read dimension bounds for "
                + session.bedrockUsername() + ": " + t);
            return new SkyWindowSession.WindowConfig(false, -64, 320, -64, 384, 0);
        }
    }

    // ------------------------------------------------------------------ world manager swap

    /**
     * Geyser keeps its {@link WorldManager} on the platform bootstrap, usually in a private field, so
     * this is the extension's single guarded reflection point. If the field cannot be found or written,
     * SkyWindow still removes block/placement desync (packet layer) but keeps the documented limitation that
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
            if (!(current instanceof WorldManager manager) || current instanceof ShiftedWorldManager) {
                throw new IllegalStateException("unexpected world manager: " + current);
            }
            field.set(bootstrap, new ShiftedWorldManager(manager, this));
            this.worldManagerField = field;
            this.originalWorldManager = manager;
            worldManagerShifted = true;
            extension.logger().info("[SkyWindow] world manager wrapped: session-aware direct reads now follow the window");
        } catch (Throwable t) {
            worldManagerShifted = false;
            extension.logger().warning("[SkyWindow] could not wrap the platform world manager (" + t
                + "); high-altitude movement corrections from Geyser-side direct world reads may still occur -"
                + " run /skywindow doctor after updating Geyser");
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
