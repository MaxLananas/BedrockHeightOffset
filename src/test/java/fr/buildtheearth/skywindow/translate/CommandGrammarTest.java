package fr.buildtheearth.skywindow.translate;

import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.CommandBlockMode;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCommandBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCommandMinecartPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Builders' contract: EVERY positional command surface must be translatable to real space, and
 * every non-positional token (selectors, NBT, deltas, block states) must survive byte-identical.
 */
class CommandGrammarTest {

    private static final CommandYRewrite.Config VANILLA =
        new CommandYRewrite.Config(true, Set.of("tppos"));
    private static final int UP = 100;

    private static void assertRewrite(String in, String expected) {
        assertEquals(expected, CommandYRewrite.rewrite(in, UP, VANILLA));
    }

    // ----------------------------------------------------- vanilla single-position commands

    @Test
    void singlePositionCommands() {
        assertRewrite("setblock 1 64 2 stone", "setblock 1 164 2 stone");
        assertRewrite("summon pig 5 100 5", "summon pig 5 200 5");
        assertRewrite("setworldspawn 4 300 4", "setworldspawn 4 400 4");
        assertRewrite("spawnpoint 4 300 4", "spawnpoint 4 400 4");
        assertRewrite("spawnpoint @s 4 300 4", "spawnpoint @s 4 400 4");
        assertRewrite("placefeature minecraft:spike 1 2 3", "placefeature minecraft:spike 1 102 3");
        assertRewrite("place template base 1 2 3", "place template base 1 102 3");
    }

    @Test
    void multiTripleCommands() {
        // fill/fillbiome: two corners, both Ys.
        assertRewrite("fill 0 10 0 3 20 3 air", "fill 0 110 0 3 120 3 air");
        assertRewrite("fillbiome 0 10 0 3 20 3 minecraft:plains", "fillbiome 0 110 0 3 120 3 minecraft:plains");
        // clone: three corners.
        assertRewrite("clone 0 5 0 2 8 2 4 6 4", "clone 0 105 0 2 108 2 4 106 4");
        // per-triple relative handling: untouched when relative, shifted when absolute.
        assertRewrite("clone 0 ~ 0 1 1 1 2 2 2", "clone 0 ~ 0 1 101 1 2 102 2");
    }

    @Test
    void entityAndSoundCommands() {
        // particle: position shifted, delta/speed/count untouched.
        assertRewrite("particle minecraft:flame 1 23 3 0.1 0.2 0.3 0 5",
            "particle minecraft:flame 1 123 3 0.1 0.2 0.3 0 5");
        // playsound: heuristics proven in CommandYRewriteTest (literal-first keeps sound specs intact).
        assertRewrite("playsound ui.button.click master 10 20 30 2.0",
            "playsound ui.button.click master 10 120 30 2.0");
        assertRewrite("playsound ui.button.click master 2.0", null);
        // tp with a facing tail: both position forms.
        assertRewrite("tp Steve 100 640 -200 facing 1 2 3",
            "tp Steve 100 740 -200 facing 1 102 3");
    }

    @Test
    void selectorAndTargetedCommands() {
        assertRewrite("damage @p 5 minecraft:fall at 1 90 1", "damage @p 5 minecraft:fall at 1 190 1");
        assertRewrite("damage @p 5", null); // no "at" segment: nothing positional
        assertRewrite("data get block 3 40 3 foo.bar", "data get block 3 140 3 foo.bar");
        assertRewrite("data get entity @s Health", null);
        assertRewrite("item replace block 0 80 0 weapon.mainhand minecraft:dirt",
            "item replace block 0 180 0 weapon.mainhand minecraft:dirt");
        assertRewrite("item replace entity @s weapon.mainhand minecraft:dirt", null);
    }

    @Test
    void lootAndWorldCommands() {
        assertRewrite("loot insert 9 18 9 loot bte:crate", "loot insert 9 118 9 loot bte:crate");
        assertRewrite("loot spawn 9 18 9 kill @s", "loot spawn 9 118 9 kill @s");
        assertRewrite("loot replace block 9 18 9 weapon bte:crate",
            "loot replace block 9 118 9 weapon bte:crate");
        assertRewrite("loot give @s mine 1 2 3 diamond_pickaxe", null);
        assertRewrite("spreadplayers 0 0 10 20 teams under 64 @a",
            "spreadplayers 0 0 10 20 teams under 164 @a");
        assertRewrite("forceload add 0 1 0 15 2 15", "forceload add 0 101 0 15 102 15");
    }

    // ----------------------------------------------------- execute chains

    @Test
    void executePositionalSegments() {
        assertRewrite("execute positioned 1 50 1 run setblock ~ ~ ~ air",
            "execute positioned 1 150 1 run setblock ~ ~ ~ air");
        assertRewrite("execute facing 1 50 1 run say hi", "execute facing 1 150 1 run say hi");
        assertRewrite("execute facing entity @s run say hi", null);
        assertRewrite("execute positioned as @e[type=pig] run setblock 1 2 3 air",
            "execute positioned as @e[type=pig] run setblock 1 102 3 air");
    }

    @Test
    void executeConditionSegments() {
        assertRewrite("execute if block 1 64 1 minecraft:stone run tp 0 100 0",
            "execute if block 1 164 1 minecraft:stone run tp 0 200 0");
        assertRewrite("execute if blocks 0 5 0 2 7 2 3 6 3 all run say ok",
            "execute if blocks 0 105 0 2 107 2 3 106 3 all run say ok");
        assertRewrite("execute unless loaded 5 6 5 run say hi",
            "execute unless loaded 5 106 5 run say hi");
        assertRewrite("execute if biome 2 30 2 minecraft:desert run say hi",
            "execute if biome 2 130 2 minecraft:desert run say hi");
        assertRewrite("execute if items block 1 2 3 container.0 minecraft:dirt run say ok",
            "execute if items block 1 102 3 container.0 minecraft:dirt run say ok");
    }

    @Test
    void executeStoreSummonAndNestedRun() {
        assertRewrite("execute store result block 0 64 0 foo.bar int 1 run say x",
            "execute store result block 0 164 0 foo.bar int 1 run say x");
        assertRewrite("execute summon pig 1 2 3 run say ok", "execute summon pig 1 102 3 run say ok");
        assertRewrite("execute as @a at @s run summon pig 1 2 3",
            "execute as @a at @s run summon pig 1 102 3");
        assertRewrite("execute run execute if block 0 64 0 air run say hi",
            "execute run execute if block 0 164 0 air run say hi");
        assertRewrite("execute as @p at @s run say hi", null); // nothing positional anywhere
        assertRewrite("execute run say hi", null);
    }

    // ----------------------------------------------------- robustness

    @Test
    void snbtSurvivesByteIdentical() {
        assertRewrite("summon pig 1 23 3 {Passengers:[{id:\"minecraft:zombie\",Tags:[\"a b\"]}]}",
            "summon pig 1 123 3 {Passengers:[{id:\"minecraft:zombie\",Tags:[\"a b\"]}]}");
        // NBT with spaces and braces must not leak into token splits.
        assertRewrite("setblock 1 64 2 minecraft:sign{front_text:{messages:[\"a b\",\"c\",1,2]}}",
            "setblock 1 164 2 minecraft:sign{front_text:{messages:[\"a b\",\"c\",1,2]}}");
    }

    @Test
    void customSchemasAndAllowlist() {
        CommandYRewrite.Config schema = new CommandYRewrite.Config(true, Set.of(), Map.of("mywarp", List.of(3)));
        assertEquals("mywarp home 10 600 20", CommandYRewrite.rewrite("mywarp home 10 500 20", UP, schema));
        assertNull(CommandYRewrite.rewrite("mywarp home 10 500 20", UP, CommandYRewrite.Config.DISABLED));
        // Legacy allowlist: first coordinate triple (tppos is not vanilla).
        assertRewrite("tppos 0 100.5 0", "tppos 0 200.5 0");
        // Explicit schema overrides the vanilla grammar for a clashing name (index 4, not the Y slot).
        CommandYRewrite.Config override = new CommandYRewrite.Config(true, Set.of(),
            Map.of("setblock", List.of(4)), true);
        assertEquals("setblock 1 2 3 164", CommandYRewrite.rewrite("setblock 1 2 3 64", UP, override));
    }

    // ----------------------------------------------------- command blocks (chat is not the only path)

    @Test
    void commandBlockEditTranslatesPositionAndText() {
        ServerboundSetCommandBlockPacket packet = new ServerboundSetCommandBlockPacket(
            Vector3i.from(1, 2, 3), "fill 0 64 0 2 66 2 air", CommandBlockMode.AUTO, true, false, false);
        ServerboundSetCommandBlockPacket out = (ServerboundSetCommandBlockPacket)
            OutboundYTransforms.apply(packet, UP, VANILLA);
        assertEquals(Vector3i.from(1, 102, 3), out.getPosition());
        assertEquals("fill 0 164 0 2 166 2 air", out.getCommand());
    }

    @Test
    void commandMinecartTextIsTranslated() {
        ServerboundSetCommandMinecartPacket packet =
            new ServerboundSetCommandMinecartPacket(7, "tp 0 64 0", true);
        ServerboundSetCommandMinecartPacket out = (ServerboundSetCommandMinecartPacket)
            OutboundYTransforms.apply(packet, UP, VANILLA);
        assertEquals("tp 0 164 0", out.getCommand());
    }

    @Test
    void commandBlockDisplaySeesWindowSpace() {
        NbtMap nbt = NbtMap.builder()
            .put("id", "minecraft:command_block")
            .put("Command", "setblock 1 64 2 stone")
            .put("CustomName", "keep me")
            .build();
        ClientboundBlockEntityDataPacket packet = new ClientboundBlockEntityDataPacket(
            Vector3i.from(1, 2, 3), BlockEntityType.COMMAND_BLOCK, nbt);
        ClientboundBlockEntityDataPacket out = (ClientboundBlockEntityDataPacket)
            InboundYTransforms.apply(packet, UP, VANILLA);
        assertEquals(Vector3i.from(1, -98, 3), out.getPosition());
        assertEquals("setblock 1 -36 2 stone", out.getNbt().get("Command"));
        assertEquals("keep me", out.getNbt().get("CustomName"));
        assertEquals("minecraft:command_block", out.getNbt().get("id"));
    }

    @Test
    void zeroOffsetIsAlwaysANoop() {
        assertNull(CommandYRewrite.rewrite("tp 0 64 0", 0, VANILLA));
    }
}
