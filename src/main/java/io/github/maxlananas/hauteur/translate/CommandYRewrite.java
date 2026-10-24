package io.github.maxlananas.hauteur.translate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the Y coordinate of absolute positions inside unsigned chat commands so that a Bedrock
 * player typing {@code /tp 120 640 -300}-style coordinates at their own location works in window space
 * like everything else. Relative coordinates ({@code ~}, {@code ^}) need no translation at all and are
 * left untouched, which also means partial-relative triples keep working.
 *
 * <p>This is deliberately minimal: it only understands the position triple of allowlisted commands
 * (default {@code tp}, {@code tppos}, {@code teleport}). Commands like {@code /setblock} or
 * {@code /fill} carry many coordinates and quoting rules that a server-side rewriter must not guess at;
 * operators editing the world through console commands should use {@code /execute positioned} or run
 * them from the Java console - see README "Commands with absolute coordinates".</p>
 *
 * <p>Only the Y token is replaced. Every other character, including the spacing between tokens, is kept
 * exactly as typed, so the command reaches the server with the same arguments it had before.</p>
 */
public final class CommandYRewrite {

    /** Longer input is never rewritten: the server caps chat commands far below this anyway. */
    static final int MAX_COMMAND_LENGTH = 512;

    /** Larger absolute values are not positions in any Java world; such a command is left alone. */
    static final double MAX_ABSOLUTE_COORDINATE = 30_000_000.0;
    private static final BigDecimal MAX_ABSOLUTE_BIG = BigDecimal.valueOf(30_000_000L);
    private static final int MAX_DECIMAL_SCALE = 40;

    /** Plain decimal syntax only: no hex, no {@code d}/{@code f} suffixes, no NaN or infinity. */
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern TOKEN = Pattern.compile("\\S+");

    public record Config(boolean enabled, Set<String> commands) {
        public static final Config DISABLED = new Config(false, Set.of());
    }

    private CommandYRewrite() {
    }

    /**
     * @param message command text, with or without leading slash, e.g. {@code tp 120 640 -300}
     * @return the rewritten message, or null when no rewrite applies
     */
    public static String rewrite(String message, int offset, Config config) {
        if (message == null || message.isEmpty() || offset == 0 || !config.enabled()) {
            return null;
        }
        if (message.length() > MAX_COMMAND_LENGTH) {
            // Keeps the rewrite linear-time on adversarial payloads and skips anything that cannot execute.
            return null;
        }
        boolean slash = message.startsWith("/");
        String body = slash ? message.substring(1) : message;
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(body);
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(), matcher.end()});
        }
        if (spans.size() < 4) { // name + x y z minimum
            return null;
        }
        String command = body.substring(spans.get(0)[0], spans.get(0)[1]).toLowerCase(Locale.ROOT);
        if (!config.commands().contains(command)) {
            return null;
        }
        String[] tokens = new String[spans.size()];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = body.substring(spans.get(i)[0], spans.get(i)[1]);
        }
        int ySlot = findYSlot(tokens);
        if (ySlot < 0) {
            return null;
        }
        Double value = parseAbsoluteNumber(tokens[ySlot]);
        if (value == null) {
            return null; // relative Y or not a plain number: leave the command alone
        }
        String replacement = shiftedText(tokens[ySlot], value, offset);
        if (replacement == null) {
            return null;
        }
        int[] span = spans.get(ySlot);
        String rewritten = body.substring(0, span[0]) + replacement + body.substring(span[1]);
        return slash ? "/" + rewritten : rewritten;
    }

    /**
     * The middle token of the first run of three consecutive coordinate-looking tokens, which is the Y
     * of the position triple for {@code tp [player] x y z [yaw [pitch]]} and {@code tppos x y z}.
     *
     * @return index of the Y token, or -1 when no complete triple exists
     */
    private static int findYSlot(String[] tokens) {
        for (int i = 1; i + 2 < tokens.length; i++) {
            if (isCoordinateToken(tokens[i]) && isCoordinateToken(tokens[i + 1]) && isCoordinateToken(tokens[i + 2])) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * The shifted Y as text, in the notation the player used. Decimal notation is shifted with exact decimal
     * arithmetic, so -15.99999 + 16 is written 0.00001 rather than the nearest binary double. Integers stay
     * integers. Returns null when the result is outside the coordinate bound.
     */
    private static String shiftedText(String original, double value, int offset) {
        if (original.indexOf('.') >= 0 || original.indexOf('e') >= 0 || original.indexOf('E') >= 0) {
            BigDecimal parsed;
            try {
                parsed = new BigDecimal(original);
            } catch (NumberFormatException e) {
                return null;
            }
            // Checked before any arithmetic: 1e-2000000000 parses as a double of 0, but adding to it would align
            // the scales and build a two-billion-digit number. No typed coordinate has this many decimals.
            if (parsed.scale() > MAX_DECIMAL_SCALE || parsed.scale() < -MAX_DECIMAL_SCALE) {
                return null;
            }
            BigDecimal exact = parsed.add(BigDecimal.valueOf(offset));
            if (exact.scale() < 1) {
                exact = exact.setScale(1); // written with a decimal point in, so written with one out (1.0e2 -> 1540.0)
            }
            if (exact.abs().compareTo(MAX_ABSOLUTE_BIG) > 0) {
                return null;
            }
            // Plain notation only: a player never types an exponent, so no exponent is written back.
            return exact.toPlainString();
        }
        double shifted = value + offset;
        if (Math.abs(shifted) > MAX_ABSOLUTE_COORDINATE) {
            return null;
        }
        return Long.toString(Math.round(shifted));
    }

    private static boolean isCoordinateToken(String token) {
        char first = token.charAt(0);
        if (first == '~' || first == '^') {
            return true;
        }
        return parseAbsoluteNumber(token) != null;
    }

    /** @return the finite value of a plain decimal token, or null when the token is not one. */
    static Double parseAbsoluteNumber(String token) {
        if (!NUMBER.matcher(token).matches()) {
            return null;
        }
        double value = Double.parseDouble(token);
        return Double.isFinite(value) ? value : null;
    }
}
