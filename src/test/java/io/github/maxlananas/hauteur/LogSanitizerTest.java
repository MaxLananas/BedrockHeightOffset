package io.github.maxlananas.hauteur;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LogSanitizerTest {

    @ParameterizedTest
    @NullAndEmptySource
    void missingNameRendersAsQuestionMark(String name) {
        assertEquals("?", LogSanitizer.playerName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Steve", "Jean-Pierre_42", "Zoë", "名前"})
    void ordinaryNamesAreKept(String name) {
        assertEquals(name, LogSanitizer.playerName(name));
    }

    @Test
    void controlCharactersCannotSplitOrForgeALogLine() {
        // \n and \r end or forge lines; NUL, DEL and C1 controls are invisible or hostile in terminals.
        String hostile = "a\nb\r\u0000c\u007F\u0085";
        assertEquals("a?b??c??", LogSanitizer.playerName(hostile));
    }

    @Test
    void bidiOverridesAndIsolatesCannotReorderALogLine() {
        // U+202E (right-to-left override) and U+2066 (left-to-right isolate) are format characters: a gamertag
        // can use them to make the rest of a log line display backwards.
        assertEquals("a?b", LogSanitizer.playerName("a\u202Eb"));
        assertEquals("a?b", LogSanitizer.playerName("a\u2066b"));
    }

    @Test
    void lineAndParagraphSeparatorsAreNeutralised() {
        assertEquals("a?b?c", LogSanitizer.playerName("a\u2028b\u2029c"));
    }

    @Test
    void zeroWidthAndByteOrderMarksAreReplaced() {
        assertEquals("a?b?c", LogSanitizer.playerName("a\u200Bb\uFEFFc"));
    }

    @Test
    void zeroWidthJoinerInEmojiSequencesIsKept() {
        // man + ZWJ + woman + ZWJ + girl: one family emoji, which must not turn into question marks.
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        assertEquals(family, LogSanitizer.playerName(family));
    }

    @Test
    void longNamesAreCutToTheLimit() {
        String name = "x".repeat(40);
        assertEquals("x".repeat(LogSanitizer.MAX_NAME_LENGTH), LogSanitizer.playerName(name));
    }

    @Test
    void cutNeverLeavesAHalfSurrogatePair() {
        // 31 ASCII chars, then an emoji (a surrogate pair) that straddles the cut at 32.
        String name = "a".repeat(31) + "\uD83D\uDE00";
        String cut = LogSanitizer.playerName(name);
        assertEquals("a".repeat(31), cut);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    }

    @Test
    void loneSurrogatesBecomeQuestionMarks() {
        // An unpaired high or low surrogate is not valid text; it is replaced, not written as-is.
        assertEquals("a?b?c", LogSanitizer.playerName("a\uD83Db\uDE00c"));
    }

    @Test
    void supplementaryCombiningMarksAreCountedAsOneMarkEach() {
        // U+1D165 (musical combining stem) is a surrogate pair. Four of them after one base: three kept, one '?'.
        String stem = "\uD834\uDD65";
        assertEquals("a" + stem.repeat(3) + "?", LogSanitizer.playerName("a" + stem.repeat(4)));
    }

    @Test
    void supplementaryEmojiIsKeptWhole() {
        assertEquals("\uD83D\uDE00x", LogSanitizer.playerName("\uD83D\uDE00x"));
    }

    @Test
    void sanitizedOutputIsAlwaysSingleLineAndBounded() {
        for (int c = 0; c <= 0xFFFF; c += 7) {
            String out = LogSanitizer.playerName("p" + (char) c + "q".repeat(60));
            assertFalse(out.indexOf('\n') >= 0 || out.indexOf('\r') >= 0, "line break survived for U+" + c);
            assertFalse(out.length() > LogSanitizer.MAX_NAME_LENGTH);
        }
    }

    @Test
    void combiningMarksAreCappedPerBaseCharacter() {
        // A stacked "zalgo" name keeps three marks per base character; the rest become '?' and the name stays on one line.
        String zalgo = "a" + "\u0301".repeat(20) + "b";
        assertEquals("a" + "\u0301".repeat(3) + "?".repeat(17) + "b", LogSanitizer.playerName(zalgo));
    }
}
