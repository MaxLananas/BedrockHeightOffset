package fr.buildtheearth.skywindow;

import fr.buildtheearth.skywindow.pipeline.SkyWindowHandler;
import fr.buildtheearth.skywindow.session.SkyWindowSession;
import fr.buildtheearth.skywindow.world.ShiftedWorldManager;
import io.netty.channel.Channel;
import org.geysermc.geyser.GeyserBootstrap;
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
    /** What the packet-shape assumptions in docs/ have been verified against; shown by /skywindow doctor. */
    public static final String TESTED_AGAINST = "Geyser 2.11.2-SNAPSHOT / MCProtocolLib master-1.28.x @ 2026-09-21; docs/PACKET-MATRIX.md carries the audit details";

    private final SkyWindowExtension extension;
    private final Map<GeyserSession, SkyWindowSession> states = new ConcurrentHashMap<>();
    private volatile SkyWindowConfig config = SkyWindowConfig.loadDefault();
    /** Derived, allocation-free hot-path view of the config; rewritten only when config reloads. */
    private volatile fr.buildtheearth.skywindow.translate.CommandYRewrite.Config commandConfig =
        deriveCommandConfig(SkyWindowConfig.loadDefault());
    private volatile boolean worldManagerShifted;
    private volatile boolean running;
    /** Saved reflection handles for whatever we replaced, for clean restore. */
    private volatile Field worldManagerField;
    private volatile WorldManager originalWorldManager;
    private volatile Field bootstrapField;
    private volatile Object originalBootstrap;

    public SkyWindowCore(SkyWindowExtension extension) {
        this.extension = extension;
    }

    public void start() {
        loadConfig();
        running = config.enabled;
        startSweeper();
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
            commandConfig = deriveCommandConfig(config);
        } catch (IOException e) {
            extension.logger().warning("[SkyWindow] could not read " + file + ", using defaults: " + e.getMessage());
            config = SkyWindowConfig.loadDefault();
        }
        commandConfig = deriveCommandConfig(config);
        for (String warning : config.warnings) {
            extension.logger().warning("[SkyWindow] config: " + warning);
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
        try {
            GeyserImpl.getInstance().getScheduledThread().scheduleWithFixedDelay(() -> {
                try {
                    for (Map.Entry<GeyserSession, SkyWindowSession> e : states.entrySet()) {
                        if (e.getKey().isClosed()) {
                            SkyWindowSession orphan = states.remove(e.getKey());
                            if (orphan != null) {
                                orphan.resetChunkCache();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // sweep never must fail the scheduler
                }
            }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            // executor unavailable: states still cleaned by the disconnect event path
        }
    }

    /** Per-type quarantine registry (deterministic transform failures), for /skywindow doctor. */
    private final Map<String, String> quarantinedTypes = new ConcurrentHashMap<>();

    public void reportQuarantinedType(String typeName, String reason) {
        quarantinedTypes.put(typeName, reason);
    }

    public Map<String, String> quarantinedTypes() {
        return quarantinedTypes;
    }

    /** Player names into logs: strip control characters (log-injection hardening, Geyser does the same). */
    public String safeName(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        int bad = -1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                bad = i;
                break;
            }
        }
        if (bad < 0) {
            return name.length() > 32 ? name.substring(0, 32) : name;
        }
        StringBuilder sb = new StringBuilder(Math.min(name.length(), 32));
        for (int i = 0; i < Math.min(name.length(), 32); i++) {
            char c = name.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        return sb.toString();
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

    public fr.buildtheearth.skywindow.translate.CommandYRewrite.Config commandConfig() {
        return commandConfig;
    }

    private static fr.buildtheearth.skywindow.translate.CommandYRewrite.Config deriveCommandConfig(
        SkyWindowConfig config) {
        return new fr.buildtheearth.skywindow.translate.CommandYRewrite.Config(
            !config.rewriteCommands.isEmpty(), config.rewriteCommands);
    }

    public SkyWindowSession state(GeyserSession session) {
        return states.get(session);
    }

    /** Manual exact-window move for {@code /skywindow window <realY>} (builder tooling). */
    public boolean forceWindow(GeyserSession session, double realY) {
        SkyWindowSession state = states.get(session);
        var channel = state == null ? null : state.channel;
        if (channel == null || !channel.isActive()) {
            return false;
        }
        var handler = channel.pipeline().get(SkyWindowHandler.HANDLER_NAME);
        if (handler instanceof SkyWindowHandler h) {
            h.forceWindow(realY);
            return true;
        }
        return false;
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
        if (session == null || !running || session.isClosed()) {
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
        pipeline.addAfter(NetworkConstants.CODEC_NAME, SkyWindowHandler.HANDLER_NAME,
            new SkyWindowHandler(session, state, this, GeyserImpl.getInstance().getLogger()));
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
     * Geyser consults the platform {@link WorldManager} for every direct, session-aware world read
     * (collision corrections, container re-checks, vehicle physics, decorated pots). The wrapper adds
     * the calling session's offset so those reads resolve in the same window frame the client lives
     * in. Two install strategies, in order of blast radius:
     *
     * <ol>
     *   <li><b>Field swap</b> - replace the bootstrap's own {@code WorldManager} field when its
     *       declared type accepts a {@link ShiftedWorldManager} (e.g. the mod platform declares
     *       {@code WorldManager}).</li>
     *   <li><b>Bootstrap proxy</b> - Geyser's Spigot bootstrap declares its field as
     *       {@code GeyserSpigotWorldManager} (a platform subclass), so a plain {@code Field.set} of
     *       our decorator is rejected by the JVM's store check. Instead we replace
     *       {@code GeyserImpl}'s {@code GeyserBootstrap} reference with a dynamic proxy whose
     *       {@code getWorldManager()} returns the decorator and which delegates every other method.
     *       {@code GeyserImpl.getWorldManager()} and {@code getBootstrap().getWorldManager()} then
     *       both see the decorator on every platform. (No Geyser core or platform code type-tests or
     *       casts the bootstrap object - audited against Geyser 2.11.x sources.)</li>
     * </ol>
     *
     * If both fail (a future Geyser that reorganizes the bootstrap), SkyWindow still removes
     * block/placement desync at the packet layer but session-aware direct world reads (collision
     * corrections) may still disagree - the log and {@code /skywindow doctor} say so explicitly.
     */
    private void installShiftedWorldManager() {
        if (worldManagerShifted) {
            return; // already wrapped by a previous enable cycle (enabled -> reload -> still enabled)
        }
        try {
            Object bootstrap = GeyserImpl.getInstance().getBootstrap();
            if (!(bootstrap instanceof GeyserBootstrap geyserBootstrap)) {
                throw new IllegalStateException("no bootstrap");
            }
            WorldManager current = geyserBootstrap.getWorldManager();
            if (current instanceof ShiftedWorldManager) {
                // Already wrapped (e.g. by a previous load that did not unwrap): treat as success.
                worldManagerShifted = true;
                return;
            }
            ShiftedWorldManager wrapper = new ShiftedWorldManager(current, this);
            String strategy = tryFieldSwap(bootstrap, wrapper)
                ? "bootstrap field"
                : tryBootstrapProxy(geyserBootstrap, wrapper)
                    ? "geyser bootstrap proxy"
                    : null;
            if (strategy == null) {
                throw new IllegalStateException("no installable seam on " + bootstrap.getClass().getName());
            }
            worldManagerShifted = true;
            extension.logger().info("[SkyWindow] world manager wrapped (" + strategy
                + "): session-aware direct reads now follow the window");
        } catch (Throwable t) {
            worldManagerShifted = false;
            extension.logger().warning("[SkyWindow] could not wrap the platform world manager (" + t
                + "); high-altitude movement corrections from Geyser-side direct world reads may still occur -"
                + " run /skywindow doctor after updating Geyser");
        }
    }

    /** Strategy 1: the bootstrap keeps its manager in a field whose declared type accepts our decorator. */
    private boolean tryFieldSwap(Object bootstrap, ShiftedWorldManager wrapper) {
        try {
            Field field = findWorldManagerField(bootstrap.getClass());
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            Object current = field.get(bootstrap);
            if (!(current instanceof WorldManager) || current instanceof ShiftedWorldManager) {
                return false;
            }
            field.set(bootstrap, wrapper);
            this.worldManagerField = field;
            this.originalWorldManager = (WorldManager) current;
            return true;
        } catch (Throwable ignored) {
            // Platform field type refused the store (or reflection is blocked): fall through.
            return false;
        }
    }

    /** Strategy 2: wrap the {@link GeyserBootstrap} reference GeyserImpl dispatches through. */
    private boolean tryBootstrapProxy(GeyserBootstrap real, ShiftedWorldManager wrapper) {
        try {
            GeyserImpl geyser = GeyserImpl.getInstance();
            Field field = findBootstrapField(geyser.getClass());
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            if (field.get(geyser) != real) {
                return false;
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                GeyserBootstrap.class.getClassLoader(),
                new Class<?>[] {GeyserBootstrap.class},
                (p, method, args) -> {
                    if ("getWorldManager".equals(method.getName()) && method.getParameterCount() == 0) {
                        return wrapper;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause() != null ? e.getCause() : e;
                    }
                });
            field.set(geyser, proxy);
            this.bootstrapField = field;
            this.originalBootstrap = real;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void unwrapWorldManager() {
        Field field = worldManagerField;
        WorldManager original = originalWorldManager;
        if (field != null && original != null) {
            try {
                Object bootstrap = GeyserImpl.getInstance().getBootstrap();
                if (bootstrap != null && field.get(bootstrap) instanceof ShiftedWorldManager) {
                    field.set(bootstrap, original);
                }
            } catch (Throwable ignored) {
                // shutdown path; a leftover wrapper disappears with the process anyway
            }
            worldManagerField = null;
            originalWorldManager = null;
        }
        Field bootstrapField = this.bootstrapField;
        Object bootstrapOriginal = this.originalBootstrap;
        if (bootstrapField != null && bootstrapOriginal != null) {
            try {
                bootstrapField.set(GeyserImpl.getInstance(), bootstrapOriginal);
            } catch (Throwable ignored) {
                // shutdown path
            }
            this.bootstrapField = null;
            this.originalBootstrap = null;
        }
        worldManagerShifted = false;
    }

    /**
     * Match by assignability to {@link ShiftedWorldManager}: only fields whose declared type would
     * actually accept the decorator (the JVM store-checks {@code Field.set}). Final and static fields
     * are skipped - we do not fight immutability Geyser relies on elsewhere.
     */
    private static Field findWorldManagerField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (WorldManager.class.isAssignableFrom(f.getType())
                    && f.getType().isAssignableFrom(ShiftedWorldManager.class)
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && !java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                    return f;
                }
            }
        }
        return null;
    }

    /** The {@link GeyserBootstrap} reference on GeyserImpl (non-static; final is acceptable here). */
    private static Field findBootstrapField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (GeyserBootstrap.class.isAssignableFrom(f.getType())
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    return f;
                }
            }
        }
        return null;
    }

    private static GeyserSession cast(GeyserConnection connection) {
        return connection instanceof GeyserSession session ? session : null;
    }
}
