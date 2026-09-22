package fr.buildtheearth.skywindow.translate;

import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.CommandProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The universal command-shape oracle: an index over the <b>Brigadier command tree the server itself
 * sends</b> ({@link ClientboundCommandsPacket}), used to locate the exact Y tokens of
 * <i>any</i> command - vanilla or plugin - without ever naming a command in code or config.
 *
 * <p>Every registered command on the server (thousands of plugin commands included) is declared in
 * that tree with typed argument nodes: {@code VEC3}/{@code BLOCK_POS} nodes are positions, so the
 * middle token of what they consume is a Y. The walker below mirrors Brigadier's parse (literals
 * first, argument fallback with backtracking, redirect expansion) to find those tokens in exactly
 * the places the server would read coordinates. This is what makes "every command works as usual"
 * structural instead of a maintained list.</p>
 *
 * <p>Fallbacks stay layered (see {@link CommandYRewrite#rewrite}): when the tree cannot parse a
 * message at all, the vanilla grammar and configured allowlists still apply. Argument widths:
 * {@code VEC3}/{@code BLOCK_POS} consume 3 tokens (Y = middle), {@code COLUMN_POS}/{@code VEC2}/
 * {@code ROTATION} consume 2 (no Y at all), {@code MESSAGE} and greedy strings consume the rest,
 * everything else consumes one token. An argument node named {@code command} (Brigadier's
 * nested-command type used by {@code execute ... run} and admin run-as commands) recursively parses
 * against the tree root. Numeric arguments named {@code y}/{@code y1}/{@code y2}/... (plugins that
 * declare plain x/y/z integers instead of a position type) are treated as Y tokens too.</p>
 */
public final class CommandTreeIndex {

    private static final int MAX_NESTED_DEPTH = 12;

    private final CommandNode[] nodes;
    private final int rootIndex;

    private CommandTreeIndex(CommandNode[] nodes, int rootIndex) {
        this.nodes = nodes;
        this.rootIndex = rootIndex;
    }

    /** @return a new index, or null when the packet carries no usable tree (nothing to index). */
    public static CommandTreeIndex build(ClientboundCommandsPacket packet) {
        CommandNode[] nodes = packet.getNodes();
        if (nodes == null || nodes.length == 0) {
            return null;
        }
        int root = packet.getFirstNodeIndex();
        if (root < 0 || root >= nodes.length) {
            return null;
        }
        return new CommandTreeIndex(nodes, root);
    }

    /**
     * Y-token indices of {@code tokens} (a command body split like {@code split(" ")}) according to
     * the server's own command tree.
     *
     * @return the set of Y indices (possibly empty, which is a definitive "no Y here"), or
     *     <b>null</b> when the tree cannot parse the message at all (typo, unknown command, cut
     *     text) - callers should then fall back to the grammar heuristics.
     */
    public BitSet ySlots(List<String> tokens) {
        BitSet slots = new BitSet(tokens.size());
        boolean ok = walk(rootIndex, 0, tokens, slots, new HashSet<>(), 0);
        return ok ? slots : null;
    }

    // ------------------------------------------------------------------ walker

    private boolean walk(int nodeIndex, int tokenIndex, List<String> tokens, BitSet slots,
                         Set<Long> failed, int depth) {
        if (depth > MAX_NESTED_DEPTH) {
            return false;
        }
        long key = ((long) nodeIndex << 32) | (tokenIndex & 0xffffffffL);
        if (failed.contains(key)) {
            return false;
        }
        CommandNode node = nodes[nodeIndex];
        if (tokenIndex >= tokens.size()) {
            return node.isExecutable(); // exact end of input lands on an executable node
        }
        int[] children = effectiveChildren(node);
        String token = tokens.get(tokenIndex);

        // Brigadier tries literal children before argument children.
        for (int childIndex : children) {
            CommandNode child = nodes[childIndex];
            if (child.getType() == CommandType.LITERAL
                && child.getName() != null && child.getName().equalsIgnoreCase(token)) {
                BitSet trial = (BitSet) slots.clone();
                if (walk(childIndex, tokenIndex + 1, tokens, trial, failed, depth)) {
                    slots.clear();
                    slots.or(trial);
                    return true;
                }
            }
        }
        for (int childIndex : children) {
            CommandNode child = nodes[childIndex];
            if (child.getType() != CommandType.ARGUMENT) {
                continue;
            }
            BitSet trial = (BitSet) slots.clone();
            int next = consume(child, tokenIndex, tokens, trial, failed, depth);
            if (next >= 0 && walk(childIndex, next, tokens, trial, failed, depth)) {
                slots.clear();
                slots.or(trial);
                return true;
            }
        }
        failed.add(key);
        return false;
    }

    /**
     * Consumes what {@code node}'s argument would read at {@code tokenIndex}, marking Y tokens into
     * {@code slots}.
     *
     * @return the token index after the argument, or -1 when the tokens cannot fit its shape
     */
    private int consume(CommandNode node, int tokenIndex, List<String> tokens, BitSet slots,
                        Set<Long> failed, int depth) {
        CommandParser parser = node.getParser();
        if (isNestedCommand(node)) {
            if (tokenIndex >= tokens.size()) {
                return -1;
            }
            // Nested command text (execute ... run / run-as commands): the server validates the
            // text itself; we best-effort translate it against the root and consume the rest.
            walk(rootIndex, tokenIndex, tokens, slots, failed, depth + 1);
            return tokens.size();
        }
        if (parser == null) {
            return tokenIndex < tokens.size() ? tokenIndex + 1 : -1;
        }
        switch (parser) {
            case VEC3, BLOCK_POS -> {
                if (tokenIndex + 3 <= tokens.size()
                    && isCoordinateToken(tokens.get(tokenIndex))
                    && isCoordinateToken(tokens.get(tokenIndex + 1))
                    && isCoordinateToken(tokens.get(tokenIndex + 2))) {
                    slots.set(tokenIndex + 1);
                    return tokenIndex + 3;
                }
                return -1;
            }
            case COLUMN_POS, VEC2, ROTATION -> {
                // x,z (column/vec2) or yaw,pitch (rotation): no Y to shift in any of them.
                if (tokenIndex + 2 <= tokens.size()
                    && isCoordinateToken(tokens.get(tokenIndex))
                    && isCoordinateToken(tokens.get(tokenIndex + 1))) {
                    return tokenIndex + 2;
                }
                return -1;
            }
            case MESSAGE -> {
                return tokens.size(); // greedy by definition
            }
            case STRING -> {
                CommandProperties properties = node.getProperties();
                if (properties == StringProperties.GREEDY_PHRASE) {
                    return tokens.size();
                }
                return tokenIndex < tokens.size() ? tokenIndex + 1 : -1;
            }
            case INTEGER, LONG, FLOAT, DOUBLE -> {
                if (isYName(node.getName())) {
                    slots.set(tokenIndex);
                }
                return tokenIndex < tokens.size() ? tokenIndex + 1 : -1;
            }
            default -> {
                return tokenIndex < tokens.size() ? tokenIndex + 1 : -1;
            }
        }
    }

    /**
     * {@code execute ... run <command>} and admin run-as commands declare their trailing text as an
     * argument named {@code command} (Brigadier's nested-command type). Recognising it by name keeps
     * this independent of parser-enum drift between protocol versions.
     */
    private static boolean isNestedCommand(CommandNode node) {
        return node.getType() == CommandType.ARGUMENT
            && node.getName() != null
            && "command".equalsIgnoreCase(node.getName());
    }

    /** Plain x/y/z integer arguments (plugin style): the Y slot is the one named y*. */
    private static boolean isYName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (n.equals("y") || n.equals("ypos") || n.equals("yposition")
            || n.equals("ycoord") || n.equals("ycoordinate")
            || n.equals("maxheight") || n.equals("ymin") || n.equals("ymax")
            || n.equals("miny") || n.equals("maxy")) {
            return true;
        }
        if (n.length() >= 2 && n.charAt(0) == 'y') {
            for (int i = 1; i < n.length(); i++) {
                if (!Character.isDigit(n.charAt(i))) {
                    return false;
                }
            }
            return true; // y1, y2, ... (x1/y1/z1 style corner commands)
        }
        return false;
    }

    /**
     * The continuations Brigadier offers after {@code node}: its own children plus, when a redirect
     * is set, the redirect target's children (loop-guarded). Alias commands are pure redirects.
     */
    private int[] effectiveChildren(CommandNode node) {
        OptionalInt redirect = node.getRedirectIndex();
        if (redirect.isEmpty()) {
            return node.getChildIndices();
        }
        List<Integer> out = new ArrayList<>();
        Set<Integer> seenNodes = new HashSet<>();
        collectChildren(node, out, seenNodes);
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    private void collectChildren(CommandNode node, List<Integer> out, Set<Integer> seenNodes) {
        for (int child : node.getChildIndices()) {
            if (child >= 0 && child < nodes.length && !out.contains(child)) {
                out.add(child);
            }
        }
        OptionalInt redirect = node.getRedirectIndex();
        if (redirect.isPresent() && seenNodes.add(redirect.getAsInt())) {
            int target = redirect.getAsInt();
            if (target >= 0 && target < nodes.length) {
                collectChildren(nodes[target], out, seenNodes);
            }
        }
    }

    private static boolean isCoordinateToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        if (first == '~' || first == '^') {
            return true;
        }
        return CommandYRewrite.parseAbsoluteNumber(token) != null;
    }
}
