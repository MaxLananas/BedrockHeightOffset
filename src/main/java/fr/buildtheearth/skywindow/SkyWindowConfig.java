package fr.buildtheearth.skywindow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Extension configuration, loaded from {@code <extensions data>/skywindow/config.properties}.
 * Properties (not YAML) because the only YAML implementation visible at extension runtime is Geyser's
 * relocated copy, which an extension must not import; see README "Configuration".
 */
public final class SkyWindowConfig {
    public boolean enabled = true;
    /** Switch when player feet reach within this many blocks of the window edge. Min 32. */
    public int switchMarginBlocks = 64;
    /** Minimum time between two window switches for the same player. */
    public long switchCooldownMs = 2000;
    /** How long outbound player position packets are held while the client snaps to the new window. */
    public long freezeMs = 400;
    /** Hold & replay block interactions sent during a switch instead of dropping them. */
    public boolean freezeHoldActions = true;
    public int chunkCacheMaxChunks = 2048;
    public int chunkCacheMaxMegabytes = 96;
    /** 0 = derive from the dimension; set to force a lower cap (safety valve). */
    public int maxOffsetBlocks = 0;
    /** Chat commands whose absolute Y is rewritten. Empty string disables rewriting. */
    public Set<String> rewriteCommands = new HashSet<>(Arrays.asList("tp", "tppos", "teleport"));
    /** Log one line per window switch (they are rare by construction). */
    public boolean logSwitches = true;

    private SkyWindowConfig() {
    }

    public static SkyWindowConfig loadDefault() {
        return new SkyWindowConfig();
    }

    public static SkyWindowConfig load(Path file) throws IOException {
        SkyWindowConfig config = new SkyWindowConfig();
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        config.enabled = bool(props, "enabled", config.enabled);
        config.switchMarginBlocks = Math.max(32, integer(props, "switch-margin-blocks", config.switchMarginBlocks));
        config.switchCooldownMs = Math.max(250, longVal(props, "switch-cooldown-ms", config.switchCooldownMs));
        config.freezeMs = Math.max(100, longVal(props, "freeze-ms", config.freezeMs));
        config.freezeHoldActions = bool(props, "freeze-hold-actions", config.freezeHoldActions);
        config.chunkCacheMaxChunks = Math.max(64, integer(props, "chunk-cache-max-chunks", config.chunkCacheMaxChunks));
        config.chunkCacheMaxMegabytes = Math.max(8, integer(props, "chunk-cache-max-megabytes", config.chunkCacheMaxMegabytes));
        config.maxOffsetBlocks = alignToSection(integer(props, "max-offset-blocks", config.maxOffsetBlocks));
        String commands = props.getProperty("rewrite-commands", "tp,tppos,teleport").toLowerCase(Locale.ROOT).trim();
        config.rewriteCommands = commands.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(commands.split("\\s*,\\s*")));
        config.logSwitches = bool(props, "log-switches", config.logSwitches);
        return config;
    }

    public static String sampleFileContents() {
        return """
            # SkyWindow configuration
            enabled=true
            # Switch the height window when the player gets within this many blocks
            # of the top/bottom edge of the client-visible range (min 32).
            switch-margin-blocks=64
            # Debounce for window switches, in milliseconds.
            switch-cooldown-ms=2000
            # Outbound movement is held for this long after a switch so the client
            # can be snapped to the new window without fighting stale positions.
            freeze-ms=400
            # Block interactions (dig/place) sent during a freeze are queued and replayed
            # afterwards (translated for the frame the client was in) instead of dropped.
            freeze-hold-actions=true
            # Original chunks kept (per player) for replaying during a switch.
            chunk-cache-max-chunks=2048
            chunk-cache-max-megabytes=96
            # 0 = automatically cap the offset so the world top stays reachable.
            max-offset-blocks=0
            # Chat commands whose absolute Y coordinate is rewritten into real space.
            # Leave empty to disable. Only unsigned commands are ever modified.
            rewrite-commands=tp,tppos,teleport
            log-switches=true
            """;
    }

    private static int alignToSection(int blocks) {
        return Math.max(0, blocks / 16 * 16);
    }

    private static boolean bool(Properties props, String key, boolean def) {
        String v = props.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties props, String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longVal(Properties props, String key, long def) {
        try {
            return Long.parseLong(props.getProperty(key, Long.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
