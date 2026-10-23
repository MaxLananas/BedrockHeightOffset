package io.github.maxlananas.hauteur.translate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        // Trailing whitespace is part of what the player typed: it is kept, only the Y token changes.
        String justUnder = "tp 0 64 0" + " ".repeat(100);
        assertTrue(justUnder.length() <= 512);
        assertEquals("tp 0 1504 0" + " ".repeat(100), CommandYRewrite.rewrite(justUnder, 1440, CFG));
        // but interior double-spaces must survive token-for-token (no accidental arg shift)
        assertEquals("tp Steve  100 1504 0", CommandYRewrite.rewrite("tp Steve  100 64 0", 1440, CFG));
    }

    @Test
    void numberFormatsArePreserved() {
        assertEquals("tp 0 1504 0", CommandYRewrite.rewrite("tp 0 64 0", 1440, CFG));
        assertEquals("tp 0 1540.0 0", CommandYRewrite.rewrite("tp 0 1.0e2 0", 1440, CFG),
            "scientific input stays a double on the way out");
        assertEquals("tp 0 64.25 0", CommandYRewrite.rewrite("tp 0 -1391.75 0", 1456, CFG));
    }

    @Test
    void doubleSpaceInsideTheTripleIsStillRewritten() {
        // Before the fix the triple was split on single spaces, so an empty token broke it and Y was not shifted.
        assertEquals("tp 100  2080 -200", CommandYRewrite.rewrite("tp 100  640 -200", 1440, CFG));
        assertEquals("tp 100\t2080 -200", CommandYRewrite.rewrite("tp 100\t640 -200", 1440, CFG));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "1f", "1d", "0x40", "1_000", "--5", "1e400"})
    void nonPlainNumbersAreNeverRewritten(String y) {
        // Double.parseDouble accepts several of these; none is a coordinate the player could have typed.
        assertNull(CommandYRewrite.rewrite("tp 100 " + y + " 200", 1440, CFG), "rewrote " + y);
    }

    @Test
    void absurdCoordinatesAreNotRewritten() {
        assertNull(CommandYRewrite.rewrite("tp 0 99999999999 0", 1440, CFG));
    }

    @Test
    void commandNameIsCaseInsensitiveWithoutLocaleSurprises() {
        assertEquals("TP 0 1504 0", CommandYRewrite.rewrite("TP 0 64 0", 1440, CFG));
    }

    @Test
    void decimalResultsNeverUseExponentNotation() {
        // Double.toString would print tiny values as 1.0E-5; a player never types that.
        for (int i = 0; i < 2000; i++) {
            double y = -16.0 + i * 0.001;
            String out = CommandYRewrite.rewrite("tppos 0 " + y + " 0", 16, CFG);
            assertNotNull(out);
            assertFalse(out.contains("E") || out.contains("e"), "exponent in " + out);
        }
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

    @Test
    void decimalShiftIsExactNotBinaryRounded() {
        // -15.99999 + 16 is 0.00001 exactly; double arithmetic would print 0.000009999999999621423.
        assertEquals("tppos 0 0.00001 0", CommandYRewrite.rewrite("tppos 0 -15.99999 0", 16, CFG));
    }

    @Test
    void decimalNotationStaysDecimalWhenTheResultIsWhole() {
        assertEquals("tp 0 1540.0 0", CommandYRewrite.rewrite("tp 0 1.0e2 0", 1440, CFG));
    }

    @Test
    void absurdExponentsAreLeftAloneInsteadOfBuildingHugeNumbers() {
        assertNull(CommandYRewrite.rewrite("tp 0 1e-2147483648 0", 16, CFG));
        assertNull(CommandYRewrite.rewrite("tp 0 1e-2000000000 0", 16, CFG));
        assertNull(CommandYRewrite.rewrite("tp 0 1e300 0", 16, CFG));
    }
}
