package fr.buildtheearth.skywindow.translate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic seeded fuzz: hostile inputs must never throw and must always uphold the pinned
 * contracts (tokenizer semantics, rewrite token counts, slash marker, length cap, exact integer
 * shift/unshift round-trips, token offsets). 64k+ inputs per family. The chunk side is fuzzed in
 * {@code SectionCodecFuzzTest}; the tree oracle is pinned in {@code CommandTreeTest}.
 */
public final class FuzzTest {

    private static final long SEED = 0x5EED_BEEF_1234L;
    private static final CommandYRewrite.Config CFG =
        new CommandYRewrite.Config(true, Set.of("tp", "tppos", "teleport", "mywarp"));
    private static final int[] OFFSETS = {1, 2, 3, 10, 100, 1440, 2016, 3000};
    private static final String[] ALPHA = {
        "tp", "setblock", "x", "y", "z", "~", "^", "1", "-5", "3.5", "1504", "2500",
        "{id:1b,x:2d}", "\"with space\"", "say", "hello world", "", " ", "0", "64", "-1440",
    };

    private static String randomCommand(Random random) {
        int length = 1 + random.nextInt(10);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0 && random.nextBoolean()) {
                builder.append(' ');
            }
            builder.append(ALPHA[random.nextInt(ALPHA.length)]);
        }
        String body = builder.toString();
        return random.nextBoolean() ? "/" + body : body;
    }

    private static String body(String message) {
        return message.startsWith("/") ? message.substring(1) : message;
    }

    @Test
    void rewriteNeverThrowsAndKeepsTheTokenContract() {
        Random random = new Random(SEED);
        int nonNullRewrites = 0;
        for (int i = 0; i < 65_536; i++) {
            // sometimes deliberately oversized to exercise the pinned 512 length cap;
            // single assignment so the value stays effectively final for the lambda below
            String input = random.nextInt(8) == 0
                ? randomCommand(random) + " " + "x".repeat(520)
                : randomCommand(random);
            int offset = OFFSETS[random.nextInt(OFFSETS.length)];
            CommandYRewrite.Config config = random.nextBoolean()
                ? CFG
                : CommandYRewrite.Config.VANILLA_DEFAULT;
            String result = assertDoesNotThrow(
                () -> CommandYRewrite.rewrite(input, offset, config),
                "rewrite must never throw for [" + input + "]");
            // pinned length cap: anything clearly over 512 characters must bail out untouched
            if (input.length() > 513) {
                assertNull(result, "over-cap command must not be rewritten: [" + input + "]");
            }
            if (result == null) {
                continue; // zero-rewrite contract: the message is left untouched
            }
            nonNullRewrites++;
            assertEquals(input.startsWith("/"), result.startsWith("/"), "slash marker");
            List<String> tokens = CommandYRewrite.tokenize(body(input));
            List<String> resultTokens = CommandYRewrite.tokenize(body(result));
            assertEquals(tokens.size(), resultTokens.size(),
                "token contract broken for [" + input + "] -> [" + result + "]");
        }
        assertTrue(nonNullRewrites > 0, "fuzz family is degenerate: zero rewrites");
    }

    @Test
    void integerShiftAndUnshiftStayExactOverTheWholeValidWindow() {
        Random random = new Random(SEED + 1);
        for (int i = 0; i < 32_768; i++) {
            int y = random.nextInt(8192) - 3000;
            int offset = OFFSETS[random.nextInt(OFFSETS.length)];
            String input = "tp 0 " + y + " 0";

            String up = assertDoesNotThrow(() -> CommandYRewrite.rewrite(input, offset, CFG));
            assertEquals("tp 0 " + (y + offset) + " 0", up, "UP shift must be exact for [" + input + "]");

            String back = assertDoesNotThrow(() -> CommandYRewrite.rewrite(up, -offset, CFG));
            assertEquals(input, back, "DOWN unshift must round-trip exactly");

            // relative and zero-offset behavior stay pinned under the same fuzz stream
            assertNull(CommandYRewrite.rewrite(input, 0, CFG), "offset 0 must never rewrite");
            assertNull(CommandYRewrite.rewrite("tp 0 ~ 0", offset, CFG), "relative Y must never rewrite");
        }
    }

    @Test
    void tokenizeWithOffsetsKeepsTheSliceContract() {
        Random random = new Random(SEED + 5);
        for (int i = 0; i < 32_768; i++) {
            String input = randomCommand(random);
            List<CommandYRewrite.Token> tokens = CommandYRewrite.tokenizeWithOffsets(input);
            List<String> plain = CommandYRewrite.tokenize(input);
            assertEquals(plain.size(), tokens.size(), "token count mismatch");
            for (int j = 0; j < tokens.size(); j++) {
                assertEquals(plain.get(j), tokens.get(j).text(), "token text mismatch");
                int start = tokens.get(j).start();
                String text = tokens.get(j).text();
                assertTrue(start >= 0 && start + text.length() <= input.length(),
                    "token out of bounds for [" + input + "]");
                // tokenizeWithOffsets restores protected spaces, so every token is the exact slice
                assertEquals(text, input.substring(start, start + text.length()),
                    "offset slice mismatch for [" + input + "]");
            }
        }
    }
}
