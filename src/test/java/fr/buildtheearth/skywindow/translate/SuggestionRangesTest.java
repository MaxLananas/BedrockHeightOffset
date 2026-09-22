package fr.buildtheearth.skywindow.translate;

import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundCommandSuggestionPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The suggestion round-trip contract: request text is translated like every command text, and the
 * response ranges are mapped back to the exact character offsets the player is editing. Every
 * offset below was traced by hand against the two strings.
 */
class SuggestionRangesTest {

    private static final CommandYRewrite.Config VANILLA =
        new CommandYRewrite.Config(true, Set.of());
    private static final CommandYRewrite.Config TREE_ONLY =
        new CommandYRewrite.Config(true, Set.of(), Map.of(), false);

    @Test
    void offsetsMapTokenByToken() {
        // original "setblock 1 64 2 sto"  <-> rewritten "setblock 1 164 2 sto"
        SuggestionRanges ranges = SuggestionRanges.between("setblock 1 64 2 sto", "setblock 1 164 2 sto");
        // The trailing word keeps its identity mapping (offsets 17..20 -> 16..19).
        assertEquals(16, ranges.mapOffset(17));
        assertEquals(19, ranges.mapOffset(20));
        // The Y token itself: start maps to its start, its end clamps to the shorter original.
        assertEquals(11, ranges.mapOffset(11));
        assertEquals(13, ranges.mapOffset(14));
        // Early offsets are identity.
        assertEquals(0, ranges.mapOffset(0));
        assertEquals(8, ranges.mapOffset(8));
        // Past the end clamps to the original's last token end (trailing spaces are not command text).
        assertEquals(19, ranges.mapOffset(31));
        assertEquals(0, ranges.mapOffset(-5));
    }

    private static ClientboundCommandSuggestionsPacket suggestions(int id, int start, int length) {
        return new ClientboundCommandSuggestionsPacket(id, start, length,
            new String[] {"stone"}, new Component[] {null});
    }

    @Test
    void responseRangesComeBackInEditingFrame() {
        SuggestionRanges ranges = SuggestionRanges.between("setblock 1 64 2 sto", "setblock 1 164 2 sto");
        // Server replaces the trailing word it saw as [17, 20).
        ClientboundCommandSuggestionsPacket back = ranges.mapBack(suggestions(7, 17, 3));
        assertEquals(7, back.getTransactionId());
        assertEquals(16, back.getStart());
        assertEquals(3, back.getLength());
        assertEquals(1, back.getMatches().length);
    }

    @Test
    void unknownTransactionAndIdentityMappingsAreForwardsafe() {
        SuggestionRanges.Journal journal = new SuggestionRanges.Journal();
        ClientboundCommandSuggestionsPacket plain = suggestions(1, 5, 2);
        assertSame(plain, journal.mapBack(plain)); // never noted: unchanged
        journal.note(2, "tp 0 64 0", "tp 0 64 0"); // identity text: nothing registered
        ClientboundCommandSuggestionsPacket second = suggestions(2, 5, 2);
        assertSame(second, journal.mapBack(second));

        // Malformed ranges never become invented numbers.
        SuggestionRanges ranges = SuggestionRanges.between("tp 1 64 0", "tp 1 164 0");
        ClientboundCommandSuggestionsPacket broken =
            new ClientboundCommandSuggestionsPacket(3, -1, 4, new String[] {"a"}, new Component[] {null});
        assertSame(broken, ranges.mapBack(broken));
    }

    @Test
    void suggestionRequestGoesThroughTheFullRewrite() {
        ServerboundCommandSuggestionPacket request =
            new ServerboundCommandSuggestionPacket(9, "setblock 1 64 2 sto");
        ServerboundCommandSuggestionPacket out = (ServerboundCommandSuggestionPacket)
            OutboundYTransforms.apply(request, 100, VANILLA);
        assertEquals("setblock 1 164 2 sto", out.getText());
        assertEquals(9, out.getTransactionId());
    }

    @Test
    void trailingSpacesOfTheTypedTextStayOutOfTheMapping() {
        // A player tab-completing right after the word keeps trailing spaces in the edit box.
        SuggestionRanges ranges = SuggestionRanges.between("tp 0 64 0   ", "tp 0 164 0");
        assertEquals(9, ranges.mapOffset(10)); // end of "tp 0 64 0", before the trailing spaces
    }

    @Test
    void mismatchedTokenCountsRefuseToGuess() {
        assertNull(SuggestionRanges.between("tp 0 64 0", "tp 0 164 0 extra"));
    }
}
