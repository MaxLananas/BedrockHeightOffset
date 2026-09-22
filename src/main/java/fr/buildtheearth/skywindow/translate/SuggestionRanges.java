package fr.buildtheearth.skywindow.translate;

import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Character-offset mapping between the command text a windowed player <b>sees and types</b> and the
 * rewritten text that actually reaches the wire.
 *
 * <p>Why this exists: tab-completion round-trips are the one place where rewriting text can desync
 * even when the command itself is translated perfectly. {@code ServerboundCommandSuggestionPacket}
 * carries a partial command; SkyWindow rewrites its coordinate Ys like every other command text. The
 * server then answers with {@code ClientboundCommandSuggestionsPacket} whose {@code start}/{@code
 * length} are offsets <i>into the rewritten text</i> - but the client applies those suggestions to
 * the text it is showing (the original). Without a mapping back, every shifted digit slides the
 * suggestion range one or more characters to the right.</p>
 *
 * <p>The mapping is exact and token-aligned: {@link CommandYRewrite} preserves the token count and
 * the separators between tokens (pinned by tests), so each token has an (original length, rewritten
 * length) pair and offsets map by walking the tokens - inside a changed token the offset clamps to
 * the original token's length (server ranges replace whole argument words, never pieces of a
 * coordinate; when they do point at the Y itself the clamp is exact).</p>
 */
public final class SuggestionRanges {

    private final int[] originalTokenLengths;
    private final int[] rewrittenTokenLengths;

    private SuggestionRanges(int[] originalTokenLengths, int[] rewrittenTokenLengths) {
        this.originalTokenLengths = originalTokenLengths;
        this.rewrittenTokenLengths = rewrittenTokenLengths;
    }

    /**
     * Builds the mapping between {@code original} (client view space) and {@code rewritten} (wire,
     * real space) - both are full command texts exactly as sent (slash included or not).
     */
    public static SuggestionRanges between(String original, String rewritten) {
        List<String> from = CommandYRewrite.tokenize(original);
        List<String> to = CommandYRewrite.tokenize(rewritten);
        if (from.size() != to.size()) {
            return null; // cannot happen for CommandYRewrite output; refuse to guess instead of skewing
        }
        int[] fromLens = new int[from.size()];
        int[] toLens = new int[to.size()];
        for (int i = 0; i < from.size(); i++) {
            fromLens[i] = from.get(i).length();
            toLens[i] = to.get(i).length();
        }
        return new SuggestionRanges(fromLens, toLens);
    }

    /**
     * Maps an offset in the rewritten (wire) text back to the original (view) text. Tokens are
     * walked with their single-space separators (identical on both sides - the rewrite contract).
     * Linearity over a few dozen tokens is irrelevant next to the packet I/O around it.
     */
    public int mapOffset(int rewrittenOffset) {
        if (rewrittenOffset <= 0) {
            return 0;
        }
        int originalPos = 0;
        int rewrittenPos = 0;
        for (int i = 0; i < rewrittenTokenLengths.length; i++) {
            int tokenStart = rewrittenPos;
            int tokenEnd = rewrittenPos + rewrittenTokenLengths[i];
            if (rewrittenOffset <= tokenEnd) {
                return originalPos + Math.min(rewrittenOffset - tokenStart, originalTokenLengths[i]);
            }
            originalPos += originalTokenLengths[i] + 1;
            rewrittenPos = tokenEnd + 1;
        }
        // Beyond the last token (a trailing separator of a trailing-space original): the last
        // token's end is the only meaningful answer - trailing spaces are not part of the command.
        return originalPos == 0 ? 0 : originalPos - 1;
    }

    /** Rewrites the suggestion range back into view space. Rebuilt only when a boundary moves. */
    public ClientboundCommandSuggestionsPacket mapBack(ClientboundCommandSuggestionsPacket packet) {
        int start = packet.getStart();
        int length = packet.getLength();
        if (start < 0 || length < 0) {
            return packet; // malformed range: forward untouched rather than invent numbers
        }
        int newStart = mapOffset(start);
        int newEnd = mapOffset(start + length);
        int newLength = Math.max(0, newEnd - newStart);
        if (newStart == start && newLength == length) {
            return packet;
        }
        return new ClientboundCommandSuggestionsPacket(
            packet.getTransactionId(), newStart, newLength, packet.getMatches(), packet.getTooltips());
    }

    // ------------------------------------------------------------------ journal

    /**
     * Per-session journal of in-flight suggestion requests (transaction id -> mapping), bounded and
     * access-ordered: a client tab-completing aggressively cannot grow it without limit, and a
     * missing entry degrades to "forward the response unchanged" (the pre-mapping behavior), never
     * to a wrong mapping.
     */
    public static final class Journal {
        private static final int MAX_IN_FLIGHT = 32;
        private final Map<Integer, SuggestionRanges> inFlight =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, SuggestionRanges> eldest) {
                    return size() > MAX_IN_FLIGHT;
                }
            };

        /** Records the mapping for a request that is about to be written to the wire. */
        public synchronized void note(int transactionId, String original, String rewritten) {
            if (original.equals(rewritten)) {
                return; // untouched text: the server's ranges are already in view space
            }
            SuggestionRanges ranges = between(original, rewritten);
            if (ranges != null) {
                inFlight.put(transactionId, ranges);
            }
        }

        /** Consumes the mapping for this response and rewrites its range into view space. */
        public synchronized ClientboundCommandSuggestionsPacket mapBack(
                ClientboundCommandSuggestionsPacket packet) {
            SuggestionRanges ranges = inFlight.remove(packet.getTransactionId());
            return ranges == null ? packet : ranges.mapBack(packet);
        }
    }
}
