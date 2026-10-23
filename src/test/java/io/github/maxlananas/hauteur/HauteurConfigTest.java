package io.github.maxlananas.hauteur;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HauteurConfigTest {

    private static HauteurConfig parse(@TempDir Path dir, String body) throws Exception {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return HauteurConfig.load(file);
    }

    @Test
    void clampsAndAligns(@TempDir Path dir) throws Exception {
        HauteurConfig c = parse(dir, """
            switch-margin-blocks=9999
            switch-cooldown-ms=-5
            freeze-ms=10
            chunk-cache-max-chunks=2
            chunk-cache-max-megabytes=99999
            max-offset-blocks=200
            """);
        assertEquals(256, c.switchMarginBlocks);
        assertEquals(250, c.switchCooldownMs);
        assertEquals(100, c.freezeMs);
        assertEquals(64, c.chunkCacheMaxChunks);
        assertEquals(1024, c.chunkCacheMaxMegabytes);
        assertEquals(192, c.maxOffsetBlocks, "forced offset must land on a section boundary");
        assertTrue(c.warnings.isEmpty(), "clamping is silent by design, only bad input warns");

        HauteurConfig low = parse(dir, """
            switch-margin-blocks=1
            max-offset-blocks=-64
            """);
        assertEquals(32, low.switchMarginBlocks);
        assertEquals(0, low.maxOffsetBlocks, "negative offsets are not a thing");
    }

    @Test
    void invalidValuesFallBackAndWarn(@TempDir Path dir) throws Exception {
        HauteurConfig c = parse(dir, """
            freeze-ms=banana
            switch-cooldown-ms=1_000
            totally-bogus=1
            """);
        assertEquals(400, c.freezeMs, "invalid value must fall back to the default");
        assertEquals(2000, c.switchCooldownMs);
        List<String> warnings = c.warnings;
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("freeze-ms")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("switch-cooldown-ms")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("totally-bogus") && w.contains("unknown")),
            "typos must be surfaced, not silently ignored: " + warnings);
    }

    @Test
    void commandSetCanBeDisabledAndSplit(@TempDir Path dir) throws Exception {
        assertEquals(Set.of(), parse(dir, "rewrite-commands=\n").rewriteCommands);
        assertEquals(Set.of(), parse(dir, "rewrite-commands=   \n").rewriteCommands);
        assertEquals(Set.of("tp"), parse(dir, "rewrite-commands=TP\n").rewriteCommands, "lower-cased");
        assertEquals(Set.of("warp", "home", "back"),
            parse(dir, "rewrite-commands=warp, home ,back\n").rewriteCommands);
    }

    @Test
    void booleansAreLenient(@TempDir Path dir) throws Exception {
        assertFalse(parse(dir, "enabled=false\n").enabled);
        assertFalse(parse(dir, "enabled= False \n").enabled);
        // anything but "true" is false - properties files have no other convention; never throw.
        assertFalse(parse(dir, "announce-switches=yes\n").announceSwitches);
        assertTrue(parse(dir, "freeze-hold-movement=TRUE\n").freezeHoldMovement);
    }

    @Test
    void sampleFileMatchesCodeDefaults(@TempDir Path dir) throws Exception {
        // Guard against docs drift: the generated sample must load to exactly the built-in defaults.
        HauteurConfig sample = parse(dir, HauteurConfig.sampleFileContents());
        HauteurConfig def = HauteurConfig.loadDefault();
        assertTrue(sample.enabled);
        assertEquals(def.switchMarginBlocks, sample.switchMarginBlocks);
        assertEquals(def.switchCooldownMs, sample.switchCooldownMs);
        assertEquals(def.freezeMs, sample.freezeMs);
        assertEquals(def.freezeHoldActions, sample.freezeHoldActions);
        assertEquals(def.freezeHoldMovement, sample.freezeHoldMovement);
        assertEquals(def.announceSwitches, sample.announceSwitches);
        assertEquals(def.chunkCacheMaxChunks, sample.chunkCacheMaxChunks);
        assertEquals(def.chunkCacheMaxMegabytes, sample.chunkCacheMaxMegabytes);
        assertEquals(def.maxOffsetBlocks, sample.maxOffsetBlocks);
        assertEquals(def.rewriteCommands, sample.rewriteCommands);
        assertEquals(def.logSwitches, sample.logSwitches);
        assertTrue(sample.warnings.isEmpty(), "the sample must not trigger any warning: " + sample.warnings);
    }

    @Test
    void missingFilePropagates(@TempDir Path dir) {
        assertThrows(java.io.IOException.class, () -> HauteurConfig.load(dir.resolve("nope.properties")));
    }

    @Test
    void malformedEscapeIsAnIoErrorNotARuntimeCrash(@TempDir Path dir) throws Exception {
        // Properties.load throws IllegalArgumentException on a bad \\u escape. The core only handles IOException,
        // so the parser must translate it, or a single typo would stop the extension from starting.
        Path file = dir.resolve("config.properties");
        Files.writeString(file, "freeze-ms=\\uZZZZ\n", StandardCharsets.UTF_8);
        java.io.IOException e = assertThrows(java.io.IOException.class, () -> HauteurConfig.load(file));
        assertTrue(e.getMessage().contains("malformed configuration"), e.getMessage());
        assertTrue(e.getCause() instanceof IllegalArgumentException, "the cause is kept for the log");
    }

    @Test
    void freezeHoldActionsFalseIsHonoured(@TempDir Path dir) throws Exception {
        HauteurConfig c = parse(dir, "freeze-hold-actions=false\n");
        assertFalse(c.freezeHoldActions);
        assertTrue(c.warnings.isEmpty());
    }

    @Test
    void invalidBooleanKeepsTheDefaultAndIsReported(@TempDir Path dir) throws Exception {
        HauteurConfig c = parse(dir, "freeze-hold-actions=flase\n");
        assertTrue(c.freezeHoldActions, "a typo must not silently disable the option");
        assertEquals(1, c.warnings.size());
        assertTrue(c.warnings.get(0).contains("freeze-hold-actions"));
    }

    @Test
    void rewriteCommandListIgnoresEmptyEntriesAndWhitespace(@TempDir Path dir) throws Exception {
        assertEquals(Set.of("tp", "tppos"), parse(dir, "rewrite-commands=tp,,tppos,\n").rewriteCommands,
            "empty entries from doubled or trailing commas are dropped");
        assertEquals(Set.of("tp", "say"), parse(dir, "rewrite-commands= TP , ,say \n").rewriteCommands,
            "whitespace is trimmed and names are lower-cased");
        assertEquals(Set.of(), parse(dir, "rewrite-commands=,,\n").rewriteCommands,
            "a list with no names disables rewriting");
    }

    @Test
    void defaultCommandListIsTheSameWhetherOrNotTheKeyIsWritten(@TempDir Path dir) throws Exception {
        assertEquals(Set.of("tp", "tppos", "teleport"), parse(dir, "enabled=true\n").rewriteCommands);
        assertEquals(HauteurConfig.loadDefault().rewriteCommands, parse(dir, "").rewriteCommands);
    }
}
