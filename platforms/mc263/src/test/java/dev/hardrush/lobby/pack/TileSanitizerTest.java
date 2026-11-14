package dev.hardrush.lobby.pack;

import static dev.hardrush.lobby.pack.PackFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

final class TileSanitizerTest {
    @Test void bannedBlocksAreRemovedFromEveryPaletteForm() {
        for (var key : List.of("palette", "palettes")) {
            var tile = tile();
            var palette = new ListTag();
            palette.add(state("minecraft:stone"));
            palette.add(state("minecraft:command_block"));
            palette.add(state("barrier"));
            tile.remove("palette");
            if (key.equals("palette")) tile.put("palette", palette);
            else { var all = new ListTag(); all.add(palette); tile.put("palettes", all); }
            var blocks = new ListTag();
            blocks.add(block(0, 0, 0, 0, null));
            var commands = new CompoundTag(); commands.putString("id", "minecraft:command_block"); commands.putString("Command", "op @a");
            blocks.add(block(1, 0, 0, 1, commands));
            blocks.add(block(2, 0, 0, 2, null));
            blocks.add(block(3, 0, 0, 7, null));
            tile.put("blocks", blocks);
            var report = TileSanitizer.sanitize(tile, SanitizeMode.INSTALL);
            assertEquals(3, report.removedBlocks(), key);
            assertEquals(Set.of("minecraft:command_block", "minecraft:barrier"), report.removedIds(), key);
            assertEquals(1, tile.getListOrEmpty("blocks").size(), key);
            assertEquals(0, tile.getListOrEmpty("blocks").getCompoundOrEmpty(0).getIntOr("state", -1), key);
        }
    }

    @Test void signsLoseClickEventsSelectorsAndTheOpFlag() {
        var tile = tile();
        tile.getListOrEmpty("palette").add(state("minecraft:oak_sign"));
        var front = new CompoundTag();
        var messages = new ListTag();
        var click = new CompoundTag();
        click.putString("text", "点我");
        var event = new CompoundTag(); event.putString("action", "run_command"); event.putString("command", "/op Mallory");
        click.put("click_event", event);
        click.put("hover_event", new CompoundTag());
        click.putString("insertion", "x");
        click.putString("color", "red");
        messages.add(click);
        var selector = new CompoundTag(); selector.putString("selector", "@a");
        messages.add(selector);
        var nested = new CompoundTag(); nested.putString("text", "a");
        var extra = new ListTag();
        var score = new CompoundTag(); score.putString("type", "score"); score.put("score", new CompoundTag());
        extra.add(score);
        var legacy = new CompoundTag(); legacy.putString("text", "b"); legacy.put("clickEvent", new CompoundTag());
        extra.add(legacy);
        nested.put("extra", extra);
        messages.add(nested);
        messages.add(StringTag.valueOf("plain"));
        front.put("messages", messages);
        front.put("filtered_messages", copyList(messages));
        var sign = new CompoundTag();
        sign.putString("id", "minecraft:sign");
        sign.put("front_text", front);
        sign.put("back_text", front.copy());
        sign.putBoolean("allow_op_features", true);
        tile.getListOrEmpty("blocks").add(block(0, 1, 0, 1, sign));

        var report = TileSanitizer.sanitize(tile, SanitizeMode.INSTALL);
        var nbt = tile.getListOrEmpty("blocks").getCompoundOrEmpty(1).getCompoundOrEmpty("nbt");
        assertFalse(nbt.contains("allow_op_features"));
        for (var side : List.of("front_text", "back_text")) for (var key : List.of("messages", "filtered_messages")) {
            var lines = nbt.getCompoundOrEmpty(side).getListOrEmpty(key);
            var first = lines.getCompoundOrEmpty(0);
            assertEquals("点我", first.getStringOr("text", ""));
            assertEquals("red", first.getStringOr("color", ""));
            assertFalse(first.contains("click_event") || first.contains("hover_event") || first.contains("insertion"));
            assertEquals(StringTag.valueOf(""), lines.get(1));
            var extras = lines.getCompoundOrEmpty(2).getListOrEmpty("extra");
            assertEquals(StringTag.valueOf(""), extras.get(0));
            assertFalse(extras.getCompoundOrEmpty(1).contains("clickEvent"));
            assertEquals(StringTag.valueOf("plain"), lines.get(3));
        }
        assertEquals(12, report.strippedTexts());
        assertEquals(0, report.removedBlocks());
    }

    @Test void textSanitizerHandlesEveryShape() {
        assertEquals(StringTag.valueOf("{\"clickEvent\":1}"), TextSanitizer.sanitize(StringTag.valueOf("{\"clickEvent\":1}")),
                "strings are literal text since 1.21.5, never parsed");
        assertEquals(StringTag.valueOf(""), TextSanitizer.sanitize(IntTag.valueOf(3)));
        assertEquals(StringTag.valueOf(""), TextSanitizer.sanitize(new ListTag()));
        var translate = new CompoundTag();
        translate.putString("translate", "chat.type.text");
        var with = new ListTag();
        with.add(IntTag.valueOf(5));
        var nbt = new CompoundTag(); nbt.putString("nbt", "Inventory"); nbt.putString("entity", "@p");
        with.add(nbt);
        translate.put("with", with);
        var clean = (CompoundTag) TextSanitizer.sanitize(translate);
        assertEquals(IntTag.valueOf(5), clean.getListOrEmpty("with").get(0));
        assertEquals(StringTag.valueOf(""), clean.getListOrEmpty("with").get(1));
        assertTrue(translate.getListOrEmpty("with").getCompoundOrEmpty(1).contains("nbt"), "input is not modified");
    }

    @Test void containersVaultsAndHivesAreEmptied() {
        var tile = tile();
        var palette = tile.getListOrEmpty("palette");
        palette.add(state("minecraft:chest"));
        palette.add(state("minecraft:vault"));
        palette.add(state("minecraft:beehive"));
        var chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.put("Items", new ListTag());
        chest.putString("LootTable", "minecraft:chests/end_city_treasure");
        var name = new CompoundTag(); name.putString("text", "箱子"); name.put("hover_event", new CompoundTag());
        chest.put("CustomName", name);
        var components = new CompoundTag();
        components.put("minecraft:container", new ListTag());
        components.put("minecraft:custom_name", StringTag.valueOf("好箱子"));
        chest.put("components", components);
        var vault = new CompoundTag();
        vault.putString("id", "minecraft:vault");
        vault.put("config", new CompoundTag()); vault.put("server_data", new CompoundTag()); vault.put("shared_data", new CompoundTag());
        var hive = new CompoundTag();
        hive.putString("id", "minecraft:beehive");
        hive.put("bees", new ListTag()); hive.put("flower_pos", new ListTag());
        var blocks = tile.getListOrEmpty("blocks");
        blocks.add(block(0, 1, 0, 1, chest));
        blocks.add(block(1, 1, 0, 2, vault));
        blocks.add(block(2, 1, 0, 3, hive));

        var report = TileSanitizer.sanitize(tile, SanitizeMode.DRAFT);
        assertEquals(2, report.clearedContainers());
        var c = blocks.getCompoundOrEmpty(1).getCompoundOrEmpty("nbt");
        assertFalse(c.contains("Items") || c.contains("LootTable"));
        assertFalse(c.getCompoundOrEmpty("CustomName").contains("hover_event"));
        assertEquals(Set.of("minecraft:custom_name"), c.getCompoundOrEmpty("components").keySet());
        var v = blocks.getCompoundOrEmpty(2).getCompoundOrEmpty("nbt");
        assertEquals(Set.of("id"), v.keySet());
        var h = blocks.getCompoundOrEmpty(3).getCompoundOrEmpty("nbt");
        assertEquals(Set.of("id"), h.keySet());
    }

    @Test void onlyDecorationEntitiesSurviveWithTrimmedItems() {
        var tile = tile();
        var entities = new ListTag();
        entities.add(entity(entityNbt("minecraft:zombie")));
        entities.add(entity(entityNbt("minecraft:command_block_minecart")));
        var stand = entityNbt("minecraft:armor_stand");
        var passengers = new ListTag(); passengers.add(entityNbt("minecraft:creeper"));
        stand.put("Passengers", passengers);
        var equipment = new CompoundTag();
        equipment.put("head", stack("minecraft:player_head", "minecraft:profile", StringTag.valueOf("Notch")));
        var sword = stack("minecraft:diamond_sword", "minecraft:enchantments", new CompoundTag());
        var named = new CompoundTag(); named.putString("text", "剑"); named.put("click_event", new CompoundTag());
        sword.getCompoundOrEmpty("components").put("minecraft:custom_name", named);
        sword.putString("Slot", "x");
        equipment.put("mainhand", sword);
        stand.put("equipment", equipment);
        entities.add(entity(stand));
        var frame = entityNbt("minecraft:item_frame");
        frame.put("Item", stack("minecraft:shulker_box", "minecraft:container", new ListTag()));
        entities.add(entity(frame));
        var display = entityNbt("text_display");
        var text = new CompoundTag(); text.putString("selector", "@e");
        display.put("text", text);
        entities.add(entity(display));
        tile.put("entities", entities);

        var report = TileSanitizer.sanitize(tile, SanitizeMode.INSTALL);
        assertEquals(2, report.removedEntities());
        assertTrue(report.removedIds().containsAll(Set.of("minecraft:zombie", "minecraft:command_block_minecart")));
        assertEquals(3, entities.size());
        var s = entities.getCompoundOrEmpty(0).getCompoundOrEmpty("nbt");
        assertFalse(s.contains("Passengers"));
        var head = s.getCompoundOrEmpty("equipment").getCompoundOrEmpty("head");
        assertEquals(Set.of("id", "count", "components"), head.keySet());
        assertTrue(head.getCompoundOrEmpty("components").contains("minecraft:profile"));
        var hand = s.getCompoundOrEmpty("equipment").getCompoundOrEmpty("mainhand");
        assertEquals(Set.of("id", "count", "components"), hand.keySet());
        assertEquals(Set.of("minecraft:custom_name"), hand.getCompoundOrEmpty("components").keySet());
        assertFalse(hand.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:custom_name").contains("click_event"));
        var item = entities.getCompoundOrEmpty(1).getCompoundOrEmpty("nbt").getCompoundOrEmpty("Item");
        assertEquals(Set.of("id", "count"), item.keySet());
        assertEquals(StringTag.valueOf(""), entities.getCompoundOrEmpty(2).getCompoundOrEmpty("nbt").get("text"));
    }

    @Test void markersGoOnInstallAndStayInDrafts() {
        for (var mode : SanitizeMode.values()) {
            var tile = tile();
            var entities = new ListTag();
            var marker = entityNbt("minecraft:armor_stand");
            marker.put("Tags", strings("hardrush.marker", "hardrush.fn.assembly"));
            entities.add(entity(marker));
            var trigger = entityNbt("minecraft:interaction");
            trigger.put("Tags", strings("hardrush.fn.rules"));
            entities.add(entity(trigger));
            tile.put("entities", entities);
            var report = TileSanitizer.sanitize(tile, mode);
            assertEquals(mode == SanitizeMode.INSTALL ? 1 : 2, entities.size(), mode.name());
            assertEquals(mode == SanitizeMode.INSTALL ? 1 : 0, report.removedEntities(), mode.name());
            assertEquals(Set.of(), report.removedIds(), "markers are not reported as unsafe");
        }
    }

    @Test void contentOutsideTheTileIsDropped() {
        var tile = tile();
        tile.put("size", ints(4096, -3, 48));
        var blocks = tile.getListOrEmpty("blocks");
        blocks.add(block(48, 0, 0, 0, null));
        blocks.add(block(0, -1, 0, 0, null));
        var entities = new ListTag();
        entities.add(entity(entityNbt("minecraft:painting")));
        var far = entity(entityNbt("minecraft:painting"));
        far.put("blockPos", ints(-5, 0, 0));
        entities.add(far);
        var drifting = entity(entityNbt("minecraft:painting"));
        var pos = new ListTag();
        for (double d : new double[] {1.5, 2, 9000}) pos.add(net.minecraft.nbt.DoubleTag.valueOf(d));
        drifting.put("pos", pos);
        entities.add(drifting);
        tile.put("entities", entities);
        var report = TileSanitizer.sanitize(tile, SanitizeMode.INSTALL);
        assertEquals(ints(48, 0, 48), tile.get("size"));
        assertEquals(1, blocks.size());
        assertEquals(1, entities.size());
        assertEquals(2, report.removedBlocks());
        assertEquals(2, report.removedEntities());
        assertEquals(Set.of(), report.removedIds());
    }

    @Test void totalsAreCapped() throws PackException {
        var tile = tile();
        var blocks = tile.getListOrEmpty("blocks");
        for (int i = 0; i < TileSanitizer.MAX_BLOCK_ENTITIES; i++) blocks.add(block(i % 48, 0, i / 48 % 48, 0, new CompoundTag()));
        TileSanitizer.checkTotals(List.of(tile));
        var more = tile();
        more.getListOrEmpty("blocks").add(block(0, 0, 0, 0, new CompoundTag()));
        assertTrue(assertThrows(PackException.class, () -> TileSanitizer.checkTotals(List.of(tile, more))).getMessage().contains("方块实体"));

        var crowd = tile();
        var entities = new ListTag();
        for (int i = 0; i <= TileSanitizer.MAX_ENTITIES; i++) entities.add(entity(entityNbt("minecraft:painting")));
        crowd.put("entities", entities);
        assertTrue(assertThrows(PackException.class, () -> TileSanitizer.checkTotals(List.of(crowd))).getMessage().contains("实体太多"));
    }

    private static CompoundTag entityNbt(String id) {
        var nbt = new CompoundTag();
        nbt.putString("id", id);
        return nbt;
    }
    private static CompoundTag entity(CompoundTag nbt) {
        var e = new CompoundTag();
        var pos = new ListTag();
        for (double d : new double[] {1.5, 2, 3.5}) pos.add(net.minecraft.nbt.DoubleTag.valueOf(d));
        e.put("pos", pos);
        e.put("blockPos", ints(1, 2, 3));
        e.put("nbt", nbt);
        return e;
    }
    private static CompoundTag stack(String id, String component, Tag value) {
        var stack = new CompoundTag();
        stack.putString("id", id);
        stack.putInt("count", 1);
        var components = new CompoundTag();
        components.put(component, value);
        components.put("minecraft:lore", new ListTag());
        stack.put("components", components);
        return stack;
    }
    private static ListTag copyList(ListTag list) {
        var out = new ArrayList<Tag>();
        for (var t : list) out.add(t.copy());
        var copy = new ListTag();
        copy.addAll(out);
        return copy;
    }
}
