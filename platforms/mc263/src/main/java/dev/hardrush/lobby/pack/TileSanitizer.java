package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.MarkerTags;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The single gate between a pack (from anyone) and a world. Works on structure template NBT that the DataFixer has
 * already brought to this game's version, before {@code StructureTemplate.load}; edits the tag in place.
 * <p>
 * Template layout ({@code StructureTemplate.save}): {@code palette} (one list of {@code {Name, Properties}}) or
 * {@code palettes} (several lists sharing indices); {@code blocks} of {@code {pos, state, nbt?}} where {@code state}
 * indexes the palette and {@code nbt} is the block entity; {@code entities} of {@code {pos, blockPos, nbt}}.
 */
public final class TileSanitizer {
    /** Whole pack, checked after every tile is sanitized. */
    static final int MAX_BLOCK_ENTITIES = 4096, MAX_ENTITIES = 1024;

    /** Removed from the template, which leaves air when placed in a fresh dimension. */
    static final Set<String> BANNED_BLOCKS = Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:test_block", "minecraft:test_instance_block",
            "minecraft:spawner", "minecraft:trial_spawner", "minecraft:end_portal", "minecraft:end_gateway",
            "minecraft:sculk_shrieker", "minecraft:moving_piston", "minecraft:nether_portal", "minecraft:barrier");
    static final Set<String> ALLOWED_ENTITIES = Set.of(
            "minecraft:armor_stand", "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting",
            "minecraft:block_display", "minecraft:item_display", "minecraft:text_display", "minecraft:interaction");
    /** Block entity keys holding items or loot. */
    private static final List<String> CONTENTS = List.of("Items", "Item", "item", "RecordItem", "Book", "LootTable", "LootTableSeed");
    /** Other block entity keys that drag state along: bee occupants and their flower. */
    private static final List<String> LIVING_STATE = List.of("bees", "flower_pos");
    /** Vault rewards come from {@code MonsterVaults}; a pack's loot table and reward history must not. */
    private static final List<String> VAULT_STATE = List.of("config", "server_data", "shared_data");
    /** Item components that only change looks. */
    private static final Set<String> ITEM_COMPONENTS = Set.of(
            "minecraft:profile", "minecraft:custom_model_data", "minecraft:dyed_color", "minecraft:enchantment_glint_override",
            "minecraft:trim", "minecraft:banner_patterns", "minecraft:base_color", "minecraft:item_model",
            "minecraft:custom_name", "minecraft:item_name");
    private static final Set<String> TEXT_COMPONENTS = Set.of("minecraft:custom_name", "minecraft:item_name");

    private TileSanitizer() {}

    public static SanitizeReport sanitize(CompoundTag tile, SanitizeMode mode) {
        var run = new Run(mode);
        clampSize(tile);
        run.blocks(tile);
        run.entities(tile);
        return new SanitizeReport(run.removedBlocks, run.clearedContainers, run.strippedTexts, run.removedEntities, run.removedIds);
    }

    public static void checkTotals(Collection<CompoundTag> tiles) throws PackException {
        long blockEntities = 0, entities = 0;
        for (var tile : tiles) {
            for (var block : tile.getListOrEmpty("blocks")) {
                if (block instanceof CompoundTag b && b.contains("nbt")) blockEntities++;
            }
            entities += tile.getListOrEmpty("entities").size();
        }
        if (blockEntities > MAX_BLOCK_ENTITIES)
            throw new PackException("大厅包里的方块实体太多：" + blockEntities + " 个，最多 " + MAX_BLOCK_ENTITIES + " 个");
        if (entities > MAX_ENTITIES)
            throw new PackException("大厅包里的实体太多：" + entities + " 个，最多 " + MAX_ENTITIES + " 个");
    }

    /**
     * Template positions are relative to the tile origin; anything outside the tile would land outside the pack's
     * bounds, possibly far away. Size is clamped for the same reason.
     */
    private static void clampSize(CompoundTag tile) {
        var size = tile.getListOrEmpty("size");
        var clamped = new ListTag();
        for (int i = 0; i < 3; i++) clamped.add(IntTag.valueOf(Math.clamp(size.getIntOr(i, 0), 0, TileKey.SIZE)));
        tile.put("size", clamped);
    }
    static boolean insideTile(ListTag pos) {
        if (pos.size() != 3) return false;
        for (int i = 0; i < 3; i++) {
            int v = pos.getIntOr(i, -1);
            if (v < 0 || v >= TileKey.SIZE) return false;
        }
        return true;
    }

    /** An entity's exact position may sit a little off its block (paintings, wall-hung frames), but not far. */
    static boolean nearTile(ListTag pos) {
        if (pos.size() != 3) return false;
        for (int i = 0; i < 3; i++) {
            double v = pos.getDoubleOr(i, Double.NaN);
            if (!(v >= -1 && v <= TileKey.SIZE + 1)) return false;
        }
        return true;
    }

    static String namespaced(String id) { return id.indexOf(':') < 0 ? "minecraft:" + id : id; }

    private static final class Run {
        final SanitizeMode mode;
        int removedBlocks, clearedContainers, strippedTexts, removedEntities;
        final Set<String> removedIds = new TreeSet<>();

        Run(SanitizeMode mode) { this.mode = mode; }

        void blocks(CompoundTag tile) {
            var palettes = new ArrayList<ListTag>();
            tile.getList("palette").ifPresent(palettes::add);
            tile.getList("palettes").ifPresent(all -> { for (var p : all) if (p instanceof ListTag list) palettes.add(list); });
            // Indices share meaning across palettes; an index is banned if any palette bans it.
            int size = palettes.stream().mapToInt(ListTag::size).min().orElse(0);
            var banned = new HashMap<Integer, String>();
            var names = new HashMap<Integer, String>();
            for (var palette : palettes) {
                for (int i = 0; i < size; i++) {
                    var name = namespaced(palette.getCompoundOrEmpty(i).getStringOr("Name", "minecraft:air"));
                    names.putIfAbsent(i, name);
                    if (BANNED_BLOCKS.contains(name)) banned.putIfAbsent(i, name);
                }
            }
            var blocks = tile.getListOrEmpty("blocks");
            for (var it = blocks.iterator(); it.hasNext(); ) {
                var block = it.next() instanceof CompoundTag c ? c : null;
                int state = block == null ? -1 : block.getIntOr("state", -1);
                if (state < 0 || state >= size || !insideTile(block.getListOrEmpty("pos"))) {
                    // Malformed entries would fail or misbehave in StructureTemplate.load.
                    it.remove(); removedBlocks++;
                    continue;
                }
                var ban = banned.get(state);
                if (ban != null) { it.remove(); removedBlocks++; removedIds.add(ban); continue; }
                block.getCompound("nbt").ifPresent(nbt -> blockEntity(nbt, names.get(state)));
            }
        }

        void blockEntity(CompoundTag nbt, String blockName) {
            boolean cleared = false;
            for (var key : CONTENTS) cleared |= nbt.remove(key) != null;
            for (var key : LIVING_STATE) nbt.remove(key);
            var id = namespaced(nbt.getStringOr("id", ""));
            if (id.equals("minecraft:vault") || "minecraft:vault".equals(blockName)) {
                for (var key : VAULT_STATE) cleared |= nbt.remove(key) != null;
            }
            if (cleared) clearedContainers++;
            // 26.3 signs carry an op flag that lets click events run commands and resolve selectors.
            nbt.remove("allow_op_features");
            for (var side : List.of("front_text", "back_text")) {
                nbt.getCompound(side).ifPresent(text -> {
                    lines(text, "messages");
                    lines(text, "filtered_messages");
                });
            }
            text(nbt, "CustomName");
            // Components a block entity was placed with; they come back when the block is picked or broken.
            nbt.getCompound("components").ifPresent(this::components);
        }

        void entities(CompoundTag tile) {
            var entities = tile.getListOrEmpty("entities");
            for (var it = entities.iterator(); it.hasNext(); ) {
                var entry = it.next() instanceof CompoundTag c ? c : null;
                var nbt = entry == null ? null : entry.getCompound("nbt").orElse(null);
                var id = nbt == null ? "" : namespaced(nbt.getStringOr("id", ""));
                if (entry == null || !insideTile(entry.getListOrEmpty("blockPos")) || !nearTile(entry.getListOrEmpty("pos"))) {
                    // Outside the tile: dropped as malformed, not reported as unsafe content.
                    it.remove(); removedEntities++;
                    continue;
                }
                if (!ALLOWED_ENTITIES.contains(id)) {
                    it.remove(); removedEntities++; removedIds.add(id.isEmpty() ? "未知实体" : id);
                    continue;
                }
                if (mode == SanitizeMode.INSTALL && hasTag(nbt, MarkerTags.MARKER)) {
                    it.remove(); removedEntities++;
                    continue;
                }
                entity(nbt, id);
            }
        }

        /** UUIDs stay: {@code StructureTemplate} drops them when it adds entities to the world. */
        void entity(CompoundTag nbt, String id) {
            nbt.remove("Passengers");
            text(nbt, "CustomName");
            if (id.equals("minecraft:text_display")) text(nbt, "text");
            // Armor stands keep equipment as {slot: stack}; frames use "Item", item displays "item".
            nbt.getCompound("equipment").ifPresent(equipment -> {
                for (var slot : List.copyOf(equipment.keySet())) {
                    var stack = equipment.getCompound(slot);
                    if (stack.isPresent()) equipment.put(slot, item(stack.get()));
                    else equipment.remove(slot);
                }
            });
            for (var key : List.of("Item", "item")) nbt.getCompound(key).ifPresent(stack -> nbt.put(key, item(stack)));
        }

        CompoundTag item(CompoundTag stack) {
            var out = new CompoundTag();
            var itemId = stack.get("id");
            if (itemId != null) out.put("id", itemId.copy());
            var count = stack.get("count");
            if (count != null) out.put("count", count.copy());
            stack.getCompound("components").ifPresent(components -> {
                var kept = components.copy();
                components(kept);
                if (!kept.isEmpty()) out.put("components", kept);
            });
            return out;
        }

        void components(CompoundTag components) {
            for (var key : List.copyOf(components.keySet())) {
                var name = namespaced(key.startsWith("!") ? key.substring(1) : key);
                if (!ITEM_COMPONENTS.contains(name)) components.remove(key);
                else if (TEXT_COMPONENTS.contains(name) && !key.startsWith("!")) text(components, key);
            }
        }

        void lines(CompoundTag side, String key) {
            side.getList(key).ifPresent(lines -> {
                var clean = new ListTag();
                for (var line : lines) clean.add(clean(line));
                side.put(key, clean);
            });
        }

        void text(CompoundTag owner, String key) {
            var value = owner.get(key);
            if (value != null) owner.put(key, clean(value));
        }

        Tag clean(Tag text) {
            var safe = TextSanitizer.sanitize(text);
            if (!safe.equals(text)) strippedTexts++;
            return safe;
        }

        static boolean hasTag(CompoundTag nbt, String tag) {
            for (var t : nbt.getListOrEmpty("Tags")) if (t.asString().filter(tag::equals).isPresent()) return true;
            return false;
        }
    }
}
