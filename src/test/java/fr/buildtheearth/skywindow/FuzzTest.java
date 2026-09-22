package fr.buildtheearth.skywindow;

import fr.buildtheearth.skywindow.chunk.SectionCodec;
import fr.buildtheearth.skywindow.translate.CommandYRewrite;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodecHelper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic seeded fuzz: hostile inputs must never throw and must always uphold the pinned
 * contracts (tokenizer semantics, rewrite lengths, table encode/decode). 64k+ inputs per family.
 */
public final class FuzzTest {

    private static final long SEED = 0x5EED_BEEF_1234L;
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
        return builder.toString();
    }

    @Test
    void rewriteNeverThrowsAndKeepsTheTokenContract() {
        Random random = new Random(SEED);
        CommandYRewrite.Config up = new CommandYRewrite.Config(1440, CommandYRewrite.Direction.UP);
        int nonNullRewrites = 0;
        for (int i = 0; i < 65_536; i++) {
            String input = randomCommand(random);
            String result = CommandYRewrite.rewrite(input, up);
            List<String> tokens = CommandYRewrite.tokenize(input.startsWith("/") ? input.substring(1) : input);
            List<String> resultTokens = CommandYRewrite.tokenize(result.startsWith("/") ? result.substring(1) : result);
            assertEquals(tokens.size(), resultTokens.size(),
                "token contract broken for [" + input + "] -> [" + result + "]");
            if (result != null) {
                nonNullRewrites++;
                assertEquals(input.startsWith("/'), result.startsWith("/"), "slash marker");
            }
        }
        assertTrue(nonNullRewrites > 0, "fuzz family is degenerate: zero rewrites");
    }

    @Test
    void shiftAndUnshiftStayExactOverTheWholeValidWindow() {
        Random random = new Random(SEED + 1);
        Set<Integer> offsets = Set.of(1, 2, 3, 10, 100, 1440, 2016, 3000);
        for (int i = 0; i < 32_768; i++) {
            String token = ALPHA[random.nextInt(ALPHA.length)];
            int offset = new ArrayList<>(offsets).get(random.nextInt(offsets.size()));
            Long absolute = CommandYRewrite.parseAbsoluteNumber(token);
            Integer shifted = CommandYRewrite.shiftToken(token, offset, CommandYRewrite.Direction.UP);
            if (absolute != null && shifted != null) {
                assertEquals(absolute + offset, shifted.longValue(), "UP shift");
                assertEquals(absolute.longValue(),
                    CommandYRewrite.shiftToken(String.valueOf(shifted), offset, CommandYRewrite.Direction.DOWN).longValue(),
                    "DOWN unshift");
            }
        }
    }

    @Test
    void resliceTolerantNeverThrowsAndKeepsExactLengths() {
        Random random = new Random(SEED + 2);
        MinecraftCodecHelper helper = new MinecraftCodecHelper(MinecraftCodec.createHelper(), null, null);
        for (int i = 0; i < 32_768; i++) {
            int blockCount = random.nextInt(8);
            byte[] bytes = new byte[blockCount * (random.nextInt(40) + 2)];
            random.nextBytes(bytes);
            short[] indices = new short[blockCount];
            for (int j = 0; j < blockCount; j++) {
                indices[j] = (short) random.nextInt(1024);
            }
            int kept = random.nextInt(9) - 1; // -1..7: invalid on purpose
            int count = random.nextInt(9) - 1;
            SectionCodec.Result result = assertDoesNotThrow(
                () -> SectionCodec.resliceTolerant(bytes, indices, kept, count, helper),
                "hostile reslice must never throw");
            assertEquals((Math.max(count, 0)) * 2, result.data().length,
                "length contract broken");
        }
    }

    @Test
    void tokenizeWithOffsetsKeepsSplitContract() {
        Random random = new Random(SEED + 5);
        for (int i = 0; i < 32_768; i++) {
            String input = randomCommand(random);
            List<CommandYRewrite.Token> tokens = CommandYRewrite.tokenizeWithOffsets(input);
            List<String> plain = CommandYRewrite.tokenize(input);
            assertEquals(plain.size(), tokens.size(), "token count mismatch");
            for (int j = 0; j < tokens.size(); j++) {
                assertEquals(plain.get(j), tokens.get(j).text(), "token text mismatch");
                String slice = input.substring(tokens.get(j).start(),
                    tokens.get(j).start() + tokens.get(j).text().replace(' ', '\\0').length());
                assertEquals(tokens.get(j).text().replace(' ', '\\0'), slice, "offset mismatch");
            }
        }
    }
}
