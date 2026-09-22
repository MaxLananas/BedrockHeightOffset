package fr.buildtheearth.skywindow.translate;

import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The universal mechanism: Y positions found through the server's own Brigadier tree - every
 * command of every plugin, with ZERO per-command code or configuration. Synthetic trees below
 * mirror what servers actually declare (including plugin commands SkyWindow has never heard of).
 */
class CommandTreeTest {

    /** Enabled, but the grammar and allowlists OFF: only the tree can find anything. */
    private static final CommandYRewrite.Config TREE_ONLY =
        new CommandYRewrite.Config(true, Set.of(), Map.of(), false);
    private static final CommandYRewrite.Config VANILLA =
        new CommandYRewrite.Config(true, Set.of());
    private static final int UP = 100;

    private static CommandNode lit(String name, int... children) {
        return new CommandNode(CommandType.LITERAL, true, true, children,
            OptionalInt.empty(), name, null, null, null);
    }

    private static CommandNode arg(String name, CommandParser parser, int... children) {
        return new CommandNode(CommandType.ARGUMENT, true, true, children,
            OptionalInt.empty(), name, parser, null, null);
    }

    private static CommandNode strArg(String name, StringProperties props, int... children) {
        return new CommandNode(CommandType.ARGUMENT, true, true, children,
            OptionalInt.empty(), name, CommandParser.STRING, props, null);
    }

    private static CommandTreeIndex index(CommandNode... nodes) {
        return CommandTreeIndex.build(new ClientboundCommandsPacket(nodes, 0));
    }

    private static void assertTreeRewrite(CommandTreeIndex tree, CommandYRewrite.Config config,
                                          String in, String expected) {
        assertEquals(expected, CommandYRewrite.rewrite(in, UP, config, tree));
    }

    @Test
    void pluginCommandNeedsZeroConfiguration() {
        // /mywarp <pos> - a plugin command SkyWindow has never heard of, declared only in the tree.
        CommandTreeIndex tree = index(
            lit("", 1),
            lit("mywarp", 2),
            arg("pos", CommandParser.VEC3));
        assertTreeRewrite(tree, TREE_ONLY, "mywarp 10 500 20", "mywarp 10 600 20");
        assertTreeRewrite(tree, TREE_ONLY, "mywarp ~ 500 ~", "mywarp ~ 600 ~");
    }

    @Test
    void messageArgumentsEatEverythingUntouched() {
        CommandTreeIndex tree = index(
            lit("", 1),
            lit("say", 2),
            arg("message", CommandParser.MESSAGE));
        assertTreeRewrite(tree, TREE_ONLY, "say hi 1 2 3", null);
    }

    @Test
    void vanillaTpContractReproducedFromTheTree() {
        // tp: two overloads, exactly like the server declares them.
        CommandTreeIndex tree = index(
            lit("", 1),
            lit("tp", 2, 3),
            arg("targets", CommandParser.ENTITY, 4),
            arg("destination", CommandParser.VEC3, 5),
            arg("destination", CommandParser.VEC3),
            arg("yaw", CommandParser.FLOAT, 6),
            arg("pitch", CommandParser.FLOAT));
        assertTreeRewrite(tree, TREE_ONLY, "tp 0 64 0", "tp 0 164 0");
        assertTreeRewrite(tree, TREE_ONLY, "tp Steve 100 640 -200", "tp Steve 100 740 -200");
        assertTreeRewrite(tree, TREE_ONLY, "tp Steve Alex", null);
        assertTreeRewrite(tree, TREE_ONLY, "tp 100 640", null);
        assertTreeRewrite(tree, TREE_ONLY, "tp Steve ~ ~ 300", null);
    }

    @Test
    void executeRunRecursesIntoTheRoot() {
        // execute positioned <pos> run <command>, plus a setblock at the root the nested text can use.
        CommandTreeIndex tree = index(
            lit("", 1, 6),
            lit("execute", 2),
            lit("positioned", 3),
            arg("pos", CommandParser.VEC3, 4),
            lit("run", 5),
            strArg("command", StringProperties.GREEDY_PHRASE),
            lit("setblock", 7),
            arg("pos", CommandParser.BLOCK_POS, 8),
            arg("state", CommandParser.ITEM_STACK));
        assertTreeRewrite(tree, TREE_ONLY,
            "execute positioned 1 50 1 run setblock 2 64 3 stone",
            "execute positioned 1 150 1 run setblock 2 164 3 stone");
        assertTreeRewrite(tree, TREE_ONLY,
            "execute positioned 1 50 1 run say 4 5 6",
            "execute positioned 1 150 1 run say 4 5 6");
    }

    @Test
    void plainIntegerXyzArgumentsShiftTheY() {
        // Plugin style: /myregion <x> <y> <z> as three plain integers.
        CommandTreeIndex tree = index(
            lit("", 1),
            lit("myregion", 2),
            arg("x", CommandParser.INTEGER, 3),
            arg("y", CommandParser.INTEGER, 4),
            arg("z", CommandParser.INTEGER));
        assertTreeRewrite(tree, TREE_ONLY, "myregion 1 500 2", "myregion 1 600 2");
        assertTreeRewrite(tree, TREE_ONLY, "myregion 1 ~ 2", null);
    }

    @Test
    void columnPositionsHaveNoY() {
        CommandTreeIndex tree = index(
            lit("", 1),
            lit("column", 2),
            arg("from", CommandParser.COLUMN_POS));
        assertTreeRewrite(tree, TREE_ONLY, "column 1 500", null);
    }

    @Test
    void treeMissFallsBackToTheGrammar() {
        // The tree declares a 1-arg "setblock": "setblock 1 64 3 stone-ish" cannot parse there,
        // so the vanilla grammar still rescues it (multi-triple scan and all).
        CommandTreeIndex narrowTree = index(
            lit("", 1),
            lit("setblock", 2),
            arg("slot", CommandParser.LONG));
        assertTreeRewrite(narrowTree, VANILLA, "setblock 1 64 3 stone-ish", "setblock 1 164 3 stone-ish");
        assertTreeRewrite(narrowTree, VANILLA, "fill 0 10 0 3 20 3 air", "fill 0 110 0 3 120 3 air");
    }

    @Test
    void emptySlotSetIsDefinitive() {
        // The tree parses "setblock 1 64 3" as a pure message command: nothing may shift, even
        // though the vanilla grammar would have read a coordinate triple and shifted its Y.
        CommandTreeIndex messageStyle = index(
            lit("", 1),
            lit("setblock", 2),
            arg("message", CommandParser.MESSAGE));
        assertTreeRewrite(messageStyle, VANILLA, "setblock 1 64 3", null);
    }

    @Test
    void underMaxHeightIsAWorldY() {
        // spreadplayers ... under <maxHeight> - a vanilla absolute Y carried by a plain integer
        // argument whose NAME says what it is.
        CommandTreeIndex tree = index(
            lit("", 1),
            lit("spreadplayers", 2),
            arg("center", CommandParser.COLUMN_POS, 3),
            arg("spread", CommandParser.DOUBLE, 4),
            arg("maxRange", CommandParser.DOUBLE, 5),
            lit("teams", 6),
            lit("under", 7),
            arg("maxHeight", CommandParser.INTEGER, 8),
            arg("targets", CommandParser.GAME_PROFILE));
        assertTreeRewrite(tree, TREE_ONLY,
            "spreadplayers 0 0 10 20 teams under 64 @a",
            "spreadplayers 0 0 10 20 teams under 164 @a");
    }

    @Test
    void literalBacktrackingPicksTheRightOverload() {
        // /warp set <pos> and /warp name <name>: a literal-vs-argument sibling pair.
        CommandTreeIndex tree = index(
            lit("", 1),
            lit("warp", 2, 4),
            lit("set", 3),
            arg("pos", CommandParser.VEC3),
            lit("name", 5),
            arg("label", CommandParser.STRING));
        assertTreeRewrite(tree, TREE_ONLY, "warp set 1 700 3", "warp set 1 800 3");
        assertTreeRewrite(tree, TREE_ONLY, "warp name base", null);
    }

    @Test
    void redirectAliasesAreFollowed() {
        // /home as an alias (redirect) of /mywarp: no arguments of its own.
        CommandNode[] nodes = {
            lit("", 1),
            lit("mywarp", 2),
            arg("pos", CommandParser.VEC3),
            new CommandNode(CommandType.LITERAL, true, true, new int[0],
                OptionalInt.of(1), "home", null, null, null),
        };
        CommandNode root = lit("", 1, 3);
        nodes[0] = root;
        CommandTreeIndex tree = CommandTreeIndex.build(new ClientboundCommandsPacket(nodes, 0));
        assertTreeRewrite(tree, TREE_ONLY, "home 10 500 20", "home 10 600 20");
    }

}
