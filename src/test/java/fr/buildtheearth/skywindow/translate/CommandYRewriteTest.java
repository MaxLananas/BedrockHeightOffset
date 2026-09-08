package fr.buildtheearth.skywindow.translate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandYRewriteTest {
    private static final CommandYRewrite.Config CFG =
        new CommandYRewrite.Config(true, Set.of("tp", "tppos", "teleport"));

    @ParameterizedTest
    @CsvSource({
        // input, offset, expected
        "'tp 100 640 -200', 1440, 'tp 100 2080 -200'",
        "'/tp 100 640 -200', 1440, '/tp 100 2080 -200'",
        "'tp Steve 100 640 -200', 1440, 'tp Steve 100 2080 -200'",
        "'tp 100 640 -200 90 0', 1440, 'tp 100 2080 -200 90 0'",
        "'tp Steve ~ ~ 300', 1440, null",           // relative Y untouched
        "'tp Steve 100 ~ 300', 1440, null",       // relative Y untouched
        "'tppos 0 100.5 0', 100, 'tppos 0 200.5 0'",              // float kept float
        "'tp 0 -64 0', 64, 'tp 0 0 0'",                            // negatives round-trip
        "'say hello', 1440, null",                                  // not allowlisted
        "'gamerule doDaylightCycle false', 1440, null",
        "'tp 100 640', 1440, null",                                 // incomplete triple
        "'tp Steve Alex', 1440, null",                              // player to player
    })
    void rewriteMatrix(String input, int offset, String expected) {
        String actual = CommandYRewrite.rewrite(input, offset, CFG);
        if (expected == null || "null".equals(expected)) {
            assertNull(actual, "should not rewrite: " + input);
        } else {
            assertEquals(expected, actual);
        }
    }

    @Test
    void noRewriteAtOffsetZeroOrDisabled() {
        assertNull(CommandYRewrite.rewrite("tp 0 640 0", 0, CFG));
        assertNull(CommandYRewrite.rewrite("tp 0 640 0", 1440, CommandYRewrite.Config.DISABLED));
    }

    @Test
    void roundTripMatchesEntityMovementTransform() {
        // A player standing at window Y, typing their absolute position, must end up in real space
        // exactly where the packet-layer transform places their position updates.
        for (int offset = 0; offset <= 1440; offset += 16) {
            int clientY = 300;
            String rewritten = CommandYRewrite.rewrite("tp 7 " + clientY + " 7", offset, CFG);
            String real = offset == 0 ? "tp 7 " + clientY + " 7" : rewritten;
            String[] parts = real.split(" ");
            assertEquals(clientY + offset, Integer.parseInt(parts[2]), "at offset " + offset);
        }
    }

    @Test
    void oversizedCommandsAreIgnored() {
        String big = "tp 0 64 0 " + "x".repeat(600);
        assertTrue(big.length() > 512);
        assertNull(CommandYRewrite.rewrite(big, 1440, CFG), "rewriter must bail on adversarial length");
        String justUnder = "tp 0 64 0" + " ".repeat(100);
        assertTrue(justUnder.length() <= 512);
        assertEquals("tp 0 1504 0" + " ".repeat(100),
            CommandYRewrite.rewrite("tp 0 64 0" + " ".repeat(100), 1440, CFG),
            "padding trailing spaces must not break the rewrite");
    }

    @Test
    void numberFormatsArePreserved() {
        assertEquals("tp 0 1504 0", CommandYRewrite.rewrite("tp 0 64 0", 1440, CFG));
        assertEquals("tp 0 1540.0 0", CommandYRewrite.rewrite("tp 0 1.0e2 0", 1440, CFG),
            "scientific input stays a double on the way out");
        assertEquals("tp 0 64.25 0", CommandYRewrite.rewrite("tp 0 -1391.75 0", 1456, CFG));
    }

    @Test
    void tildeAndCaretTriplesAreLeftAlone() {
        assertNull(CommandYRewrite.rewrite("tp ~ ~ ~", 1440, CFG));
        assertNull(CommandYRewrite.rewrite("tp ^ ^ ^", 1440, CFG));
        // a run of three where only X and Z are absolute: Y is relative, so nothing is rewritten
        assertNull(CommandYRewrite.rewrite("tp 100 ~ 300", 1440, CFG));
        // but absolute Y with relative neighbours still rewrites (vanilla allows x ~ z mixed forms)
        assertEquals("tp ~ 1504 ~", CommandYRewrite.rewrite("tp ~ 64 ~", 1440, CFG));
    }
}

