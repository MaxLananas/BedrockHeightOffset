package io.github.maxlananas.altus;

/**
 * Makes player-supplied text safe to put in a log line. Bedrock gamertags are client-controlled, so a name
 * can carry control characters that split or forge log lines.
 */
public final class LogSanitizer {
    /** Longest name written to logs. Longer names are cut, so one player cannot flood a line. */
    public static final int MAX_NAME_LENGTH = 32;

    private LogSanitizer() {
    }

    /**
     * Returns a single-line rendering of a player name: C0 and C1 control characters become {@code '?'}, the
     * result is at most {@link #MAX_NAME_LENGTH} chars, and a cut never splits a surrogate pair. A null or
     * empty name renders as {@code "?"}.
     */
    public static String playerName(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        int length = Math.min(name.length(), MAX_NAME_LENGTH);
        if (length < name.length() && Character.isHighSurrogate(name.charAt(length - 1))) {
            length--;
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            sb.append(isControl(c) ? '?' : c);
        }
        return sb.toString();
    }

    private static boolean isControl(char c) {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F);
    }
}
