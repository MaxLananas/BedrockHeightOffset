package fr.buildtheearth.skywindow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
    /** Movement sent during a freeze is held and replayed in its original frame instead of dropped (elytra/boats). */
    public boolean freezeHoldMovement = true;
    /** Tell the player, in chat, when their height window was re-homed (transparency over the snap). */
    public boolean announceSwitches = false;
    public int chunkCacheMaxChunks = 2048;
    public int chunkCacheMaxMegabytes = 96;
    /** 0 = derive from the dimension; set to force a lower cap (safety valve). */
    public int maxOffsetBlocks = 0;
    /** Chat commands whose absolute Y is rewritten. Empty string disables rewriting. */
    public Set<String> rewriteCommands = new HashSet<>(Arrays.asList("tp", "tppos", "teleport"));
    /** Log one line per window switch (they are rare by construction). */
    public boolean logSwitches = true;
    /** Parse-time findings (invalid values, unknown keys); logged by the core, never fatal. */
    public List<String> warnings = List.of();

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
        java.util.List<String> warnings = new java.util.ArrayList<>();
        warnUnknownKeys(props, warnings);
        config.enabled = bool(props, "enabled", config.enabled);
        config.switchMarginBlocks = Math.min(256, Math.max(32, integer(props, "switch-margin-blocks", config.switchMarginBlocks, warnings)));
        config.switchCooldownMs = Math.min(60_000L, Math.max(250L, longVal(props, "switch-cooldown-ms", config.switchCooldownMs, warnings)));
        config.freezeMs = Math.min(5_000L, Math.max(100L, longVal(props, "freeze-ms", config.freezeMs, warnings)));
        config.freezeHoldActions = bool(props, "freeze-hold-actions", config.freezeHoldActions);
        config.freezeHoldMovement = bool(props, "freeze-hold-movement", config.freezeHoldMovement);
        config.announceSwitches = bool(props, "announce-switches", config.announceSwitches);
        config.chunkCacheMaxChunks = Math.min(32_768, Math.max(64, integer(props, "chunk-cache-max-chunks", config.chunkCacheMaxChunks, warnings)));
        config.chunkCacheMaxMegabytes = Math.min(1_024, Math.max(8, integer(props, "chunk-cache-max-megabytes", config.chunkCacheMaxMegabytes, warnings)));
        config.maxOffsetBlocks = alignToSection(integer(props, "max-offset-blocks", config.maxOffsetBlocks, warnings));
        String commands = props.getProperty("rewrite-commands", "tp,tppos,teleport").toLowerCase(Locale.ROOT).trim();
        config.rewriteCommands = commands.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(commands.split("\\s*,\\s*")));
        config.logSwitches = bool(props, "log-switches", config.logSwitches);
        config.warnings = List.copyOf(warnings);
        return config;
    }

    public static String sampleFileContents() {
        return """
            # SkyWindow configuration
            enabled=true
            # Switch the height window when the player gets within this many blocks
            # of the top/bottom edge of the client-visible range (clamped to 32..256).
            switch-margin-blocks=64
            # Debounce for window switches, in milliseconds (clamped to 250..60000).
            switch-cooldown-ms=2000
            # Outbound movement is held for this long after a switch so the client
            # can be snapped to the new window without fighting stale positions (clamped to 100..5000).
            freeze-ms=400
            # Block interactions (dig/place) sent during a freeze are queued and replayed
            # afterwards (translated for the frame the client was in) instead of dropped.
            freeze-hold-actions=true
            # Movement sent during a freeze is likewise held and replayed in the frame it was sent
            # in (prevents elytra/boat/fall progress from being discarded at every switch).
            freeze-hold-movement=true
            # Tell players in chat when their height window is re-homed (~1 per few hundred blocks).
            announce-switches=false
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
        return integer(props, key, def, null);
    }

    private static int integer(Properties props, String key, int def, List<String> warnings) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            if (warnings != null) {
                warnings.add("invalid value for '" + key + "': '" + raw + "' (using " + def + ")");
            }
            return def;
        }
    }

    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
        "enabled", "switch-margin-blocks", "switch-cooldown-ms", "freeze-ms",
        "freeze-hold-actions", "freeze-hold-movement", "announce-switches",
        "chunk-cache-max-chunks", "chunk-cache-max-megabytes", "max-offset-blocks",
        "rewrite-commands", "log-switches");

    private static void warnUnknownKeys(Properties props, List<String> warnings) {
        for (String key : props.stringPropertyNames()) {
            if (!KNOWN_KEYS.contains(key)) {
                warnings.add("unknown config key '" + key + "' (ignored; see the sample file for valid keys)");
            }
        }
    }

    private static long longVal(Properties props, String key, long def) {
        return longVal(props, key, def, null);
    }

    private static long longVal(Properties props, String key, long def, List<String> warnings) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            if (warnings != null) {
                warnings.add("invalid value for '" + key + "': '" + raw + "' (using " + def + ")");
            }
            return def;
        }
    }
}
