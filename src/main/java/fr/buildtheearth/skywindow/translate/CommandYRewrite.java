package fr.buildtheearth.skywindow.translate;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites the Y coordinate of absolute positions inside unsigned commands so that a Bedrock player
 * typing {@code /tp 120 640 -300}-style coordinates (their F3 view is window space) lands in real
 * space like every other packet the client sends. Relative coordinates ({@code ~}, {@code ^}) are
 * resolved by the server against the sender's server-side position - which is REAL space - so they
 * need no translation and are never touched; partial-relative triples keep working.
 *
 * <p><b>Coverage</b> (this is the builders' contract): the whole vanilla command surface that can
 * carry a position is understood, including recursive {@code execute ... run <command>} chains:</p>
 * <ul>
 *   <li>{@code tp}/{@code teleport} (position form, entity+position form, {@code facing <pos>}</li>
 *   <li>{@code setblock}, {@code fill}, {@code clone}, {@code fillbiome} (all their position triples)</li>
 *   <li>{@code summon}, {@code particle}, {@code playsound}, {@code placefeature}, {@code place}</li>
 *   <li>{@code damage ... at <pos>}, {@code setworldspawn}, {@code spawnpoint}, {@code forceload}</li>
 *   <li>{@code data ... block <pos>}, {@code item ... block <pos>}, {@code loot insert|spawn|replace block <pos>}</li>
 *   <li>{@code spreadplayers ... under <maxHeight>} (a single absolute Y)</li>
 *   <li>{@code execute positioned <pos>|facing <pos>|if/unless block/blocks/loaded/biome/items block|summon [<pos>]|store ... block <pos>|run <command>}</li>
 * </ul>
 *
 * <p>Everything not in that table (plugin commands) can be taught through {@link Config#schemas()}
 * with explicit Y-token indices, or - legacy behavior - through {@link Config#commands()} which maps
 * a command to "shift the Y of its first coordinate triple". Signed commands are never modified at
 * all (the gate lives in the packet layer, not here).</p>
 *
 * <p><b>Tokenization:</b> the command is split on spaces exactly like a naive {@code split(" ")},
 * which means interior empty tokens survive (double spaces are preserved token-for-token) and
 * trailing spaces are normalized away - tests pin both. SNBT regions ({@code {...}}, {@code [...]},
 * quoted strings) are protected before splitting so NBT with spaces stays one token.</p>
 */
public final class CommandYRewrite {

    /**
     * @param enabled  master switch
     * @param commands custom (non-vanilla) command names whose FIRST coordinate triple is rewritten
     * @param schemas  custom command name -> 0-based indices of the Y tokens in the full token list
     *                 (index 0 is the command name itself); overrides both vanilla and {@code commands}
     * @param vanilla  whether the built-in vanilla grammar is active
     */
    public record Config(boolean enabled, Set<String> commands, Map<String, List<Integer>> schemas,
                         boolean vanilla) {
        public static final Config DISABLED = new Config(false, Set.of(), Map.of(), false);
        /** Full vanilla grammar on, no custom commands: the packet layer's default. */
        public static final Config VANILLA_DEFAULT = new Config(true, Set.of(), Map.of(), true);

        public Config(boolean enabled, Set<String> commands) {
            this(enabled, commands, Map.of(), true);
        }

        public Config(boolean enabled, Set<String> commands, Map<String, List<Integer>> schemas) {
            this(enabled, commands, schemas, true);
        }
    }

    private static final int MAX_LENGTH = 512;
    private static final int MAX_RUN_DEPTH = 8;
    /** Subcommand words that delimit segments of an {@code execute} chain. */
    private static final Set<String> CHAIN_KEYWORDS = Set.of(
        "positioned", "facing", "align", "anchored", "rotated", "in", "on", "as", "at",
        "summon", "store", "run", "if", "unless");

    private CommandYRewrite() {
    }

    /**
     * @param message command text, with or without leading slash, e.g. {@code tp 120 640 -300}
     * @return the rewritten message, or null when no rewrite applies
     */
    public static String rewrite(String message, int offset, Config config) {
        return rewrite(message, offset, config, null);
    }

    /**
     * Same, with the server's command tree as primary shape oracle. When {@code tree} parses the
     * message, its exact Y slots win (and an empty slot set is a definitive "nothing to shift");
     * when it cannot parse the message at all, the vanilla grammar and configured allowlists still
     * apply. This is what makes every plugin command work without configuration.
     */
    public static String rewrite(String message, int offset, Config config, CommandTreeIndex tree) {
        if (message == null || message.isEmpty() || offset == 0 || !config.enabled()) {
            return null;
        }
        if (message.length() > MAX_LENGTH) {
            // A 1.21 chat command is capped at 256 characters by the server anyway; this bound keeps
            // the rewrite linear-time on adversarial payloads and skips anything that cannot execute.
            return null;
        }
        boolean slash = message.charAt(0) == '/';
        String trimmed = slash ? message.substring(1) : message;
        if (trimmed.isEmpty()) {
            return null;
        }
        // Tokenize exactly once per rewrite: the tree oracle consumes the same token/offset list
        // (EXTREME-AUDIT H2 - this runs per command packet and per tab-completion keystroke).
        List<Token> rawTokens = tokenizeWithOffsets(trimmed);
        List<String> tokens = new ArrayList<>(rawTokens.size());
        for (int i = 0; i < rawTokens.size(); i++) {
            tokens.add(rawTokens.get(i).text());
        }
        if (tokens.size() < 2) {
            return null;
        }
        boolean fromTree = false;
        BitSet ySlots;
        if (tree != null) {
            BitSet treeSlots = tree.ySlots(trimmed, rawTokens);
            if (treeSlots != null) {
                ySlots = treeSlots;
                fromTree = true;
            } else {
                ySlots = new BitSet(tokens.size());
            }
        } else {
            ySlots = new BitSet(tokens.size());
        }
        if (!fromTree) {
            collectSlots(tokens, 0, config, ySlots, 0);
        }
        List<String> out = new ArrayList<>(tokens);
        boolean any = false;
        for (int i = ySlots.nextSetBit(0); i >= 0; i = ySlots.nextSetBit(i + 1)) {
            Double value = parseAbsoluteNumber(out.get(i));
            if (value == null) {
                continue; // relative Y or unparseable: leave the token alone
            }
            out.set(i, formatLike(out.get(i), value + offset));
            any = true;
        }
        if (!any) {
            return null;
        }
        String body = String.join(" ", out);
        return slash ? "/" + body : body;
    }

    // ------------------------------------------------------------------ tokenization

    /** One token of the {@code split(" ")} contract plus its character offset in the source text. */
    record Token(String text, int start) {
    }

    /**
     * {@code split(" ")} semantics (interior empties kept, trailing dropped) with SNBT and quoted
     * regions protected so their spaces do not create tokens. The offset-carrying form drives both
     * the rewrite (token splicing) and the tree oracle (character ranges) from ONE segmentation.
     */
    static List<Token> tokenizeWithOffsets(String command) {
        char[] chars = command.toCharArray();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else if (c == ' ') {
                    chars[i] = '\0';
                }
                continue;
            }
            switch (c) {
                case '"' -> quoted = true;
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    if (depth > 0) {
                        depth--;
                    }
                }
                case ' ' -> {
                    if (depth > 0) {
                        chars[i] = '\0';
                    }
                }
                default -> {
                }
            }
        }
        String protectedText = new String(chars);
        List<Token> out = new ArrayList<>();
        int segStart = 0;
        for (int i = 0; i < protectedText.length(); i++) {
            if (protectedText.charAt(i) == ' ') {
                out.add(new Token(protectedText.substring(segStart, i).replace('\0', ' '), segStart));
                segStart = i + 1;
            }
        }
        out.add(new Token(protectedText.substring(segStart).replace('\0', ' '), segStart));
        while (!out.isEmpty() && out.get(out.size() - 1).text().isEmpty()) {
            out.remove(out.size() - 1); // split(" ") drops trailing empties
        }
        return out;
    }

    /** {@link #tokenizeWithOffsets} without the offsets - the {@code split(" ")} contract. */
    static List<String> tokenize(String command) {
        List<Token> tokens = tokenizeWithOffsets(command);
        List<String> out = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            out.add(token.text());
        }
        return out;
    }

    // ------------------------------------------------------------------ grammar

    private static void collectSlots(List<String> tokens, int rootIndex, Config config,
                                     BitSet slots, int depth) {
        if (rootIndex >= tokens.size() || depth > MAX_RUN_DEPTH) {
            return;
        }
        String root = tokens.get(rootIndex).toLowerCase(Locale.ROOT);
        int before = slots.cardinality();

        List<Integer> schema = config.schemas().get(root);
        if (schema != null) {
            for (int index : schema) {
                int abs = index; // schema indices are absolute in the full token list
                if (abs >= 0 && abs < tokens.size() && isCoordinateToken(tokens.get(abs))) {
                    slots.set(abs);
                }
            }
            return;
        }

        if ("execute".equals(root)) {
            collectExecute(tokens, rootIndex + 1, config, slots, depth);
            return;
        }

        if (config.vanilla()) {
            switch (root) {
                case "tp", "teleport" -> tpSlots(tokens, rootIndex, slots);
                case "setblock", "setworldspawn", "spawnpoint", "placefeature" ->
                    firstTriple(tokens, rootIndex + 1, slots);
                case "summon", "particle", "playsound", "place" -> firstTriple(tokens, rootIndex + 2, slots);
                case "fill", "fillbiome" -> tripleRun(tokens, rootIndex + 1, 2, slots);
                case "clone" -> tripleRun(tokens, rootIndex + 1, 3, slots);
                case "damage" -> literalThenTriple(tokens, rootIndex + 1, "at", slots);
                case "data", "item" -> blockKeywordTriple(tokens, rootIndex + 1, slots);
                case "loot" -> lootSlots(tokens, rootIndex + 1, slots);
                case "spreadplayers" -> underNumber(tokens, rootIndex + 1, slots);
                case "forceload" -> forceloadSlots(tokens, rootIndex + 1, slots);
                default -> {
                }
            }
        }
        if (slots.cardinality() == before && config.commands().contains(root)) {
            // Legacy allowlist semantics: custom commands (tppos-style) shift their FIRST
            // coordinate triple; plugin commands with richer shapes use Config#schemas instead.
            firstTriple(tokens, rootIndex + 1, slots);
        }
    }

    /** {@code tp <loc> [...] | tp <targets> <loc> [...]}, plus a {@code facing <pos>} tail. */
    private static void tpSlots(List<String> tokens, int rootIndex, BitSet slots) {
        int first = firstTriple(tokens, rootIndex + 1, slots);
        if (first < 0) {
            return; // entity-to-entity or incomplete: nothing to do
        }
        int facing = indexOf(tokens, first, "facing");
        if (facing >= 0 && facing + 1 < tokens.size()
            && !"entity".equalsIgnoreCase(tokens.get(facing + 1))) {
            firstTriple(tokens, facing + 1, slots);
        }
    }

    /** {@code execute} chain: segment by subcommand keyword, translate what each segment carries. */
    private static void collectExecute(List<String> tokens, int from, Config config,
                                       BitSet slots, int depth) {
        int i = from;
        while (i < tokens.size()) {
            String segment = tokens.get(i).toLowerCase(Locale.ROOT);
            switch (segment) {
                case "run" -> {
                    collectSlots(tokens, i + 1, config, slots, depth + 1);
                    return;
                }
                case "positioned" -> {
                    if (i + 1 < tokens.size() && !"as".equalsIgnoreCase(tokens.get(i + 1))) {
                        firstTriple(tokens, i + 1, slots);
                    }
                    i = nextKeyword(tokens, i + 1);
                }
                case "facing" -> {
                    if (i + 1 < tokens.size() && !"entity".equalsIgnoreCase(tokens.get(i + 1))) {
                        firstTriple(tokens, i + 1, slots);
                    }
                    i = nextKeyword(tokens, i + 1);
                }
                case "if", "unless" -> {
                    conditionSlots(tokens, i + 1, slots);
                    i = nextKeyword(tokens, i + 1);
                }
                case "summon" -> {
                    firstTriple(tokens, i + 2, slots);
                    i = nextKeyword(tokens, i + 1);
                }
                case "store" -> {
                    blockKeywordTriple(tokens, i + 1, slots);
                    i = nextKeyword(tokens, i + 1);
                }
                default -> i = nextKeyword(tokens, i + 1);
            }
            if (i < 0) {
                return;
            }
        }
    }

    private static void conditionSlots(List<String> tokens, int at, BitSet slots) {
        if (at >= tokens.size()) {
            return;
        }
        switch (tokens.get(at).toLowerCase(Locale.ROOT)) {
            case "block", "loaded", "biome" -> firstTriple(tokens, at + 1, slots);
            case "blocks" -> tripleRun(tokens, at + 1, 3, slots);
            case "items" -> {
                if (at + 1 < tokens.size() && "block".equalsIgnoreCase(tokens.get(at + 1))) {
                    firstTriple(tokens, at + 2, slots);
                }
            }
            default -> {
            }
        }
    }

    private static void lootSlots(List<String> tokens, int from, BitSet slots) {
        if (from >= tokens.size()) {
            return;
        }
        String sub = tokens.get(from).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "insert", "spawn", "drop" -> firstTriple(tokens, from + 1, slots);
            case "replace" -> {
                if (from + 1 < tokens.size() && "block".equalsIgnoreCase(tokens.get(from + 1))) {
                    firstTriple(tokens, from + 2, slots);
                }
            }
            default -> {
            }
        }
    }

    private static void forceloadSlots(List<String> tokens, int from, BitSet slots) {
        if (from < tokens.size() && "add".equalsIgnoreCase(tokens.get(from))) {
            int first = firstTriple(tokens, from + 1, slots);
            if (first >= 0) {
                firstTripleAt(tokens, first + 3, slots); // optional second corner, fixed slot
            }
        }
    }

    /** {@code data|item ... block <pos> ...} and {@code execute store ... block <pos> ...}. */
    private static void blockKeywordTriple(List<String> tokens, int from, BitSet slots) {
        for (int i = from; i < tokens.size() && i < from + 3; i++) {
            if ("block".equalsIgnoreCase(tokens.get(i))) {
                firstTriple(tokens, i + 1, slots);
                return;
            }
        }
    }

    /** {@code damage <target> <amount> [<type>] at <pos>}. */
    private static void literalThenTriple(List<String> tokens, int from, String literal, BitSet slots) {
        int at = indexOf(tokens, from, literal);
        if (at >= 0) {
            firstTriple(tokens, at + 1, slots);
        }
    }

    /** {@code spreadplayers ... under <maxHeight> <targets>} - one absolute Y. */
    private static void underNumber(List<String> tokens, int from, BitSet slots) {
        int at = indexOf(tokens, from, "under");
        if (at >= 0 && at + 1 < tokens.size() && isCoordinateToken(tokens.get(at + 1))) {
            slots.set(at + 1);
        }
    }

    /**
     * Marks the Y token of the first coordinate triple at or after {@code from}.
     *
     * @return the index of the triple's X token, or -1 when none exists
     */
    private static int firstTriple(List<String> tokens, int from, BitSet slots) {
        for (int i = from; i + 2 < tokens.size(); i++) {
            if (isCoordinateToken(tokens.get(i)) && isCoordinateToken(tokens.get(i + 1))
                && isCoordinateToken(tokens.get(i + 2))) {
                slots.set(i + 1);
                return i;
            }
        }
        return -1;
    }

    /** Like {@link #firstTriple} but only at the exact index (used for known-adjacent triples). */
    private static void firstTripleAt(List<String> tokens, int index, BitSet slots) {
        if (index + 2 < tokens.size()
            && isCoordinateToken(tokens.get(index)) && isCoordinateToken(tokens.get(index + 1))
            && isCoordinateToken(tokens.get(index + 2))) {
            slots.set(index + 1);
        }
    }

    /** {@code count} consecutive coordinate triples starting at {@code from}. */
    private static void tripleRun(List<String> tokens, int from, int count, BitSet slots) {
        int i = firstTriple(tokens, from, slots);
        for (int n = 1; n < count && i >= 0; n++) {
            int next = i + 3;
            if (next + 2 < tokens.size()
                && isCoordinateToken(tokens.get(next)) && isCoordinateToken(tokens.get(next + 1))
                && isCoordinateToken(tokens.get(next + 2))) {
                slots.set(next + 1);
                i = next;
            } else {
                return;
            }
        }
    }

    private static int nextKeyword(List<String> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (CHAIN_KEYWORDS.contains(tokens.get(i).toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        return tokens.size();
    }

    private static int indexOf(List<String> tokens, int from, String literal) {
        for (int i = from; i < tokens.size(); i++) {
            if (literal.equalsIgnoreCase(tokens.get(i))) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ number handling

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

    /**
     * A parsed absolute number, or null when the token is relative ({@code ~}/{@code ^}) or not a
     * number at all. Package-visible for {@link CommandTreeIndex}.
     */
    static Double parseAbsoluteNumber(String token) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
