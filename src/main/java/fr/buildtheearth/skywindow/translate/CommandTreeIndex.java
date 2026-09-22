package fr.buildtheearth.skywindow.translate;

import fr.buildtheearth.skywindow.brigadier.CommandDispatcher;
import fr.buildtheearth.skywindow.brigadier.ParseResults;
import fr.buildtheearth.skywindow.brigadier.arguments.ArgumentType;
import fr.buildtheearth.skywindow.brigadier.builder.ArgumentBuilder;
import fr.buildtheearth.skywindow.brigadier.builder.LiteralArgumentBuilder;
import fr.buildtheearth.skywindow.brigadier.builder.RequiredArgumentBuilder;
import fr.buildtheearth.skywindow.brigadier.context.ParsedCommandNode;
import fr.buildtheearth.skywindow.brigadier.tree.CommandNode;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.CommandProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The universal command-shape oracle: an index over the <b>Brigadier command tree the server itself
 * sends</b> ({@code ClientboundCommandsPacket}), locating the exact Y tokens of <i>any</i> command -
 * vanilla or plugin - without ever naming a command in code or config.
 *
 * <p><b>Primary engine: Mojang Brigadier</b> (vendored, MIT - see THIRD-PARTY-NOTICES.md). The wire
 * tree is rebuilt into real Brigadier nodes and parsed with Minecraft's own parser: literal-first
 * matching, argument fallback with backtracking, redirect aliases, numeric ranges from the declared
 * argument properties, and command-syntax errors. Character ranges of Y arguments come straight out
 * of the parse.</p>
 *
 * <p><b>Fallback engine: the built-in walker</b> (kept deliberately). If the tree cannot be rebuilt
 * (a redirect cycle in a hostile wire tree, an internal parse failure), the structural walker below
 * answers the same question; if both cannot parse a message, callers fall back to the vanilla
 * grammar. Nothing throws at a player, ever.</p>
 */
public final class CommandTreeIndex {

    private static final int MAX_NESTED_DEPTH = 12;
    private static final Object SOURCE = new Object();

    private final org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode[] nodes;
    private final int rootIndex;
    private final CommandSpans.RootHolder rootHolder = new CommandSpans.RootHolder();
    private final CommandDispatcher<Object> dispatcher;

    private CommandTreeIndex(org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode[] nodes,
                             int rootIndex) {
        this.nodes = nodes;
        this.rootIndex = rootIndex;
        this.dispatcher = rebuild();
        this.rootHolder.set(this.dispatcher, SOURCE);
    }

    /** @return a new index, or null when the packet carries no usable tree (nothing to index). */
    public static CommandTreeIndex build(
            org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket packet) {
        org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode[] nodes = packet.getNodes();
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
     * Y-token indices of the command body (no leading slash) under the exact tokenizer contract.
     *
     * @return the set of Y indices (possibly empty = a definitive "no Y here"), or <b>null</b> when
     *     the tree cannot parse the message at all (typo, unknown command, cut text) - callers then
     *     fall back to the grammar heuristics.
     */
    public BitSet ySlots(String body) {
        List<CommandYRewrite.Token> tokens = CommandYRewrite.tokenizeWithOffsets(body);
        BitSet parsed = dispatcher == null ? null : parseWith(body, tokens);
        return parsed != null ? parsed : walkerSlots(tokens);
    }

    // ------------------------------------------------------------------ primary: Mojang Brigadier

    private BitSet parseWith(String body, List<CommandYRewrite.Token> tokens) {
        ParseResults<Object> results;
        try {
            results = dispatcher.parse(body, SOURCE);
        } catch (RuntimeException e) {
            return null;
        }
        if (results.getReader().canRead()) {
            return null; // trailing junk or a shape failure somewhere: not a definitive answer
        }
        List<ParsedCommandNode<Object>> path = results.getContext().getNodes();
        if (path.isEmpty() || path.get(path.size() - 1).getNode().getCommand() == null) {
            return null; // ended on a non-executable node: the server would reject the input
        }
        BitSet slots = new BitSet(tokens.size());
        for (var argument : results.getContext().getArguments().values()) {
            Object value = argument.getResult();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof CommandSpans.Span span) {
                        int index = tokenIndexAt(tokens, span.start());
                        if (index >= 0) {
                            slots.set(index);
                        }
                    }
                }
            }
        }
        return slots; // definitive, even when empty
    }

    private static int tokenIndexAt(List<CommandYRewrite.Token> tokens, int charOffset) {
        for (int i = 0; i < tokens.size(); i++) {
            CommandYRewrite.Token token = tokens.get(i);
            if (charOffset < token.start() + Math.max(token.text().length(), 1)
                && charOffset >= token.start()) {
                return i;
            }
        }
        return -1;
    }

    /** Rebuilds the wire tree into Brigadier nodes; returns null when the wire is hostile/unbuildable. */
    private CommandDispatcher<Object> rebuild() {
        try {
            int[] order = topoOrderByRedirect();
            if (order == null) {
                return null; // redirect cycle: walker's job
            }
            @SuppressWarnings("unchecked")
            CommandNode<Object>[] built = new CommandNode[nodes.length];
            for (int index : order) {
                org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode wire = nodes[index];
                built[index] = buildNode(wire, index, built);
            }
            for (int index : order) {
                if (built[index] == null) {
                    continue;
                }
                for (int child : nodes[index].getChildIndices()) {
                    if (child >= 0 && child < nodes.length && built[child] != null) {
                        built[index].addChild(built[child]);
                    }
                }
            }
            // A root redirect (execute ... run style) means "the rest is a full command from the
            // root": hang the target's children under that node explicitly - Brigadier's redirect
            // already provides this at parse time, but a target of the ROOT node needs this wiring.
            for (int index : order) {
                if (built[index] == null) {
                    continue;
                }
                org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode wire = nodes[index];
                if (wire.getRedirectIndex().isPresent() && wire.getRedirectIndex().getAsInt() == rootIndex) {
                    for (int child : nodes[rootIndex].getChildIndices()) {
                        if (child >= 0 && child < nodes.length && built[child] != null) {
                            built[index].addChild(built[child]);
                        }
                    }
                }
            }
            CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
            if (built[rootIndex] != null) {
                for (CommandNode<Object> child : built[rootIndex].getChildren()) {
                    dispatcher.getRoot().addChild(child);
                }
            } else {
                for (int child : nodes[rootIndex].getChildIndices()) {
                    if (child >= 0 && child < nodes.length && built[child] != null) {
                        dispatcher.getRoot().addChild(built[child]);
                    }
                }
            }
            return dispatcher;
        } catch (RuntimeException e) {
            return null; // never let a hostile tree break loading: the walker takes over
        }
    }

    private CommandNode<Object> buildNode(org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode wire,
                                          int index, CommandNode<Object>[] built) {
        if (wire.getType() == CommandType.ROOT) {
            return null; // placeholder: its children go straight onto our dispatcher root
        }
        String name = wire.getName() != null && !wire.getName().isEmpty()
            ? wire.getName() : "arg" + index;
        ArgumentBuilder<Object, ?> builder;
        if (wire.getType() == CommandType.LITERAL) {
            builder = LiteralArgumentBuilder.literal(name);
        } else {
            builder = RequiredArgumentBuilder.argument(name, argumentTypeFor(wire, index));
        }
        if (wire.isExecutable()) {
            builder.executes(context -> 1);
        }
        OptionalInt redirect = wire.getRedirectIndex();
        if (redirect.isPresent()) {
            int target = redirect.getAsInt();
            if (target == rootIndex) {
                // root redirect: handled by the root-children wiring pass in rebuild() plus the
                // NESTED_COMMAND argument kind for span discovery.
                return builder.build();
            }
            if (target >= 0 && target < nodes.length && built[target] != null) {
                builder.redirect(built[target]);
            }
        }
        return builder.build();
    }

    private ArgumentType<List<CommandSpans.Span>> argumentTypeFor(
            org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode wire, int index) {
        if (isNestedCommand(wire) || redirectsToRoot(wire)) {
            return CommandSpans.of(CommandSpans.Kind.NESTED_COMMAND, false, rootHolder);
        }
        org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser parser = wire.getParser();
        if (parser == null) {
            return CommandSpans.of(CommandSpans.Kind.SINGLE, isYName(wire.getName()), rootHolder);
        }
        switch (parser) {
            case VEC3, BLOCK_POS -> {
                return CommandSpans.of(CommandSpans.Kind.POSITION3, false, rootHolder);
            }
            case COLUMN_POS, VEC2, ROTATION -> {
                return CommandSpans.of(CommandSpans.Kind.POSITION2, false, rootHolder);
            }
            case MESSAGE -> {
                return CommandSpans.of(CommandSpans.Kind.GREEDY_TEXT, false, rootHolder);
            }
            case STRING -> {
                CommandProperties properties = wire.getProperties();
                if (properties == StringProperties.GREEDY_PHRASE) {
                    return CommandSpans.of(CommandSpans.Kind.GREEDY_TEXT, false, rootHolder);
                }
                return CommandSpans.of(CommandSpans.Kind.SINGLE, isYName(wire.getName()), rootHolder);
            }
            case INTEGER, LONG, FLOAT, DOUBLE -> {
                return CommandSpans.of(CommandSpans.Kind.SINGLE, isYName(wire.getName()), rootHolder);
            }
            default -> {
                return CommandSpans.of(CommandSpans.Kind.SINGLE, isYName(wire.getName()), rootHolder);
            }
        }
    }

    private boolean redirectsToRoot(org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode wire) {
        return wire.getRedirectIndex().isPresent() && wire.getRedirectIndex().getAsInt() == rootIndex;
    }

    /**
     * Builds every node after its redirect target (redirect is final at build time in Brigadier).
     * Returns null on a redirect cycle - hostile wire data falls back to the walker.
     */
    private int[] topoOrderByRedirect() {
        int[] state = new int[nodes.length]; // 0 unseen, 1 visiting, 2 done
        List<Integer> out = new ArrayList<>(nodes.length);
        for (int i = 0; i < nodes.length; i++) {
            if (!visitRedirect(i, state, out)) {
                return null;
            }
        }
        int[] order = new int[out.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = out.get(i);
        }
        return order;
    }

    private boolean visitRedirect(int index, int[] state, List<Integer> out) {
        if (state[index] == 2) {
            return true;
        }
        if (state[index] == 1) {
            return false; // cycle
        }
        state[index] = 1;
        OptionalInt redirect = nodes[index].getRedirectIndex();
        if (redirect.isPresent()) {
            int target = redirect.getAsInt();
            if (target != rootIndex && target >= 0 && target < nodes.length
                && !visitRedirect(target, state, out)) {
                return false;
            }
        }
        state[index] = 2;
        out.add(index);
        return true;
    }

    // ------------------------------------------------------------------ fallback: structural walker

    private BitSet walkerSlots(List<CommandYRewrite.Token> tokens) {
        List<String> texts = new ArrayList<>(tokens.size());
        for (CommandYRewrite.Token token : tokens) {
            texts.add(token.text());
        }
        BitSet slots = new BitSet(tokens.size());
        boolean ok = walk(rootIndex, 0, texts, slots, new HashSet<>(), 0);
        return ok ? slots : null;
    }

    private boolean walk(int nodeIndex, int tokenIndex, List<String> tokens, BitSet slots,
                         Set<Long> failed, int depth) {
        if (depth > MAX_NESTED_DEPTH) {
            return false;
        }
        long key = ((long) nodeIndex << 32) | (tokenIndex & 0xffffffffL);
        if (failed.contains(key)) {
            return false;
        }
        org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode node = nodes[nodeIndex];
        if (tokenIndex >= tokens.size()) {
            return node.isExecutable();
        }
        int[] children = effectiveChildren(node);
        String token = tokens.get(tokenIndex);
        for (int childIndex : children) {
            org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode child = nodes[childIndex];
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
            org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode child = nodes[childIndex];
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

    private int consume(org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode node,
                        int tokenIndex, List<String> tokens, BitSet slots, Set<Long> failed, int depth) {
        if (isNestedCommand(node) || redirectsToRoot(node)) {
            if (tokenIndex >= tokens.size()) {
                return -1;
            }
            walk(rootIndex, tokenIndex, tokens, slots, failed, depth + 1);
            return tokens.size();
        }
        org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser parser = node.getParser();
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
                if (tokenIndex + 2 <= tokens.size()
                    && isCoordinateToken(tokens.get(tokenIndex))
                    && isCoordinateToken(tokens.get(tokenIndex + 1))) {
                    return tokenIndex + 2;
                }
                return -1;
            }
            case MESSAGE -> {
                return tokens.size();
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

    private static boolean isNestedCommand(
            org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode node) {
        return node.getType() == CommandType.ARGUMENT
            && node.getName() != null
            && "command".equalsIgnoreCase(node.getName());
    }

    /** Plain x/y/z numeric arguments (plugin style): the Y slot is the one named y*. */
    static boolean isYName(String name) {
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

    private int[] effectiveChildren(org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode node) {
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

    private void collectChildren(org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode node,
                                 List<Integer> out, Set<Integer> seenNodes) {
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
