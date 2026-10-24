package io.github.maxlananas.hauteur;

/**
 * Makes player-supplied text safe to put in a log line. Bedrock gamertags are client-controlled, so a name
 * can carry control characters that split or forge log lines.
 */
public final class LogSanitizer {
    /** Longest name written to logs. Longer names are cut, so one player cannot flood a line. */
    public static final int MAX_NAME_LENGTH = 32;
    /** Combining marks kept after one base character. Enough for stacked vowel and tone marks in real scripts. */
    static final int MAX_COMBINING_MARKS = 3;
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private LogSanitizer() {
    }

    /**
     * Returns a single-line rendering of a player name. Control characters (C0, DEL, C1), format characters
     * (bidirectional overrides and isolates, zero-width spaces, byte-order marks), line or paragraph separators
     * and unpaired surrogates become {@code '?'}, so a name cannot reorder, split or forge a log line. The zero-width joiner
     * U+200D is kept on purpose, so emoji sequences stay readable. Combining marks are kept up to
     * {@link #MAX_COMBINING_MARKS} per base character; more are replaced, so a stacked "zalgo" name cannot
     * bloat a log line. The result is at most {@link #MAX_NAME_LENGTH} chars, and a cut never splits a surrogate
     * pair. A null or empty name renders as {@code "?"}.
     */
    public static String playerName(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        int end = Math.min(name.length(), MAX_NAME_LENGTH);
        if (end < name.length() && Character.isHighSurrogate(name.charAt(end - 1))) {
            end--; // the cut would split a surrogate pair; drop the lone high half
        }
        StringBuilder sb = new StringBuilder(end);
        int marks = 0;
        // Walk code points, not UTF-16 units: a supplementary mark is one mark, and a lone surrogate is one '?'.
        for (int i = 0; i < end; ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (isCombiningMark(cp)) {
                marks++;
                if (marks > MAX_COMBINING_MARKS) {
                    sb.append('?');
                } else {
                    sb.appendCodePoint(cp);
                }
                continue;
            }
            marks = 0;
            if (isUnsafe(cp)) {
                sb.append('?');
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    private static boolean isCombiningMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
            || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isUnsafe(int cp) {
        if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) {
            return true; // C0 controls, DEL, C1 controls
        }
        if (cp == ZERO_WIDTH_JOINER) {
            // Joins emoji sequences and hides nothing by itself; replacing it would garble names like family emoji.
            return false;
        }
        int type = Character.getType(cp);
        return type == Character.SURROGATE // an unpaired surrogate is not valid text
            || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }
}
