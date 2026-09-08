package fr.buildtheearth.skywindow.translate;

import java.util.Set;

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
 */
public final class CommandYRewrite {
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
        if (message.length() > 512) {
            // A 1.21 chat command is capped at 256 characters by the server anyway; this bound keeps
            // the rewrite linear-time on adversarial payloads and skips anything that cannot execute.
            return null;
        }
        boolean slash = message.startsWith("/");
        String trimmed = slash ? message.substring(1) : message;
        String[] tokens = trimmed.split(" ");
        if (tokens.length < 4) { // name + x y z minimum
            return null;
        }
        String command = tokens[0].toLowerCase();
        if (!config.commands().contains(command)) {
            return null;
        }
        int ySlot = findYSlot(tokens);
        if (ySlot < 0) {
            return null;
        }
        Double value = parseAbsoluteNumber(tokens[ySlot]);
        if (value == null) {
            return null; // relative Y or unparseable: leave the command alone
        }
        double shifted = value + offset;
        String replacement = formatLike(tokens[ySlot], shifted);
        StringBuilder body = new StringBuilder(trimmed.length() + 16);
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                body.append(' ');
            }
            body.append(i == ySlot ? replacement : tokens[i]);
        }
        return slash ? "/" + body : body.toString();
    }

    /**
     * The middle token of the first run of three consecutive coordinate-looking tokens, which is the Y
     * of the position triple for {@code tp [player] x y z [yaw [pitch]]} and {@code tppos x y z}.
     *
     * @return index of the Y token, or -1 when no complete triple exists
     */
    static int findYSlot(String[] tokens) {
        for (int i = 1; i + 2 < tokens.length; i++) {
            if (isCoordinateToken(tokens[i]) && isCoordinateToken(tokens[i + 1]) && isCoordinateToken(tokens[i + 2])) {
                return i + 1;
            }
        }
        return -1;
    }

    private static String formatLike(String original, double value) {
        if (original.contains(".") || original.contains("e") || original.contains("E")) {
            return Double.toString(value);
        }
        return Long.toString((long) Math.rint(value));
    }

    private static boolean isCoordinateToken(String token) {
        if (token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        if (first == '~' || first == '^') {
            return true;
        }
        return parseAbsoluteNumber(token) != null;
    }

    static Double parseAbsoluteNumber(String token) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
