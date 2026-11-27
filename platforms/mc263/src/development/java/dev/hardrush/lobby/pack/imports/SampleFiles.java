package dev.hardrush.lobby.pack.imports;

import dev.hardrush.core.lobby.plan.Cell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.*;

/**
 * Building files built strictly from the public format specifications (no WorldEdit or Litematica was available
 * to export real ones). Each sample is a tag tree plus the blocks it must convert to, in coordinates relative to
 * the building's minimum corner; tests write the tree to disk, or damage it first.
 */
public final class SampleFiles {
    public static final int DATA_VERSION = 3900;

    /** What a sample must convert to: canonical state key per non-air cell, and block entities. */
    public record Expected(int sx, int sy, int sz, Map<Cell, String> states, Map<Cell, CompoundTag> blockEntities) {}

    public record Sample(CompoundTag tag, Expected expected) {}

    public static final String STONE = "minecraft:stone";
    public static final String STAIRS = "minecraft:oak_stairs[facing=north,half=bottom]";
    public static final String SIGN = "minecraft:oak_sign[rotation=8,waterlogged=false]";
    public static final String GOLD = "minecraft:gold_block";

    private SampleFiles() {}

    public static Path write(Path dir, String name, CompoundTag tag) throws IOException {
        var file = dir.resolve(name);
        NbtIo.writeCompressed(tag, file);
        return file;
    }

    /** The sign's block entity as a template stores it (with id, without position). */
    public static CompoundTag signEntity(String text) {
        var front = new CompoundTag();
        front.putBoolean("has_glowing_text", false);
        front.putString("color", "black");
        var lines = new ListTag();
        for (var line : List.of(text, "", "", "")) lines.add(StringTag.valueOf("\"" + line + "\""));
        front.put("messages", lines);
        var sign = new CompoundTag();
        sign.putString("id", "minecraft:sign");
        sign.putBoolean("is_waxed", false);
        sign.put("front_text", front);
        return sign;
    }

    private static CompoundTag stateTag(String state) {
        int open = state.indexOf('[');
        var tag = new CompoundTag();
        tag.putString("Name", open < 0 ? state : state.substring(0, open));
        if (open >= 0) {
            var props = new CompoundTag();
            for (var entry : state.substring(open + 1, state.length() - 1).split(",")) {
                var kv = entry.split("=");
                props.putString(kv[0], kv[1]);
            }
            tag.put("Properties", props);
        }
        return tag;
    }

    private static ListTag ints(int... values) {
        var list = new ListTag();
        for (int v : values) list.add(IntTag.valueOf(v));
        return list;
    }

    // ---- .nbt ----

    /** 3×3×3: a bottom slab, a sign with text, a chest holding an item, air, a structure void and one entity. */
    public static Sample nbt() {
        var palette = new ListTag();
        for (var state : List.of("minecraft:oak_slab[type=bottom,waterlogged=false]", SIGN,
                "minecraft:chest[facing=north,type=single,waterlogged=false]", "minecraft:air", "minecraft:structure_void"))
            palette.add(stateTag(state));
        var chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        var items = new ListTag();
        var item = new CompoundTag();
        item.putByte("Slot", (byte) 4);
        item.putString("id", "minecraft:diamond");
        item.putInt("count", 3);
        items.add(item);
        chest.put("Items", items);

        var blocks = new ListTag();
        blocks.add(block(0, 0, 0, 0, null));
        blocks.add(block(1, 0, 1, 1, signEntity("hello")));
        blocks.add(block(2, 2, 2, 2, chest));
        blocks.add(block(1, 1, 1, 3, null));
        blocks.add(block(0, 2, 0, 4, null));
        var entity = new CompoundTag();
        entity.put("pos", new ListTag());
        entity.put("blockPos", ints(0, 0, 0));
        var entities = new ListTag();
        entities.add(entity);

        var root = new CompoundTag();
        root.put("size", ints(3, 3, 3));
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", entities);
        root.putInt("DataVersion", DATA_VERSION);

        var states = new LinkedHashMap<Cell, String>();
        states.put(new Cell(0, 0, 0), "minecraft:oak_slab[type=bottom,waterlogged=false]");
        states.put(new Cell(1, 0, 1), SIGN);
        states.put(new Cell(2, 2, 2), "minecraft:chest[facing=north,type=single,waterlogged=false]");
        var entitiesExpected = Map.of(new Cell(1, 0, 1), signEntity("hello"), new Cell(2, 2, 2), chest.copy());
        return new Sample(root, new Expected(3, 3, 3, states, entitiesExpected));
    }

    private static CompoundTag block(int x, int y, int z, int state, CompoundTag nbt) {
        var block = new CompoundTag();
        block.put("pos", ints(x, y, z));
        block.putInt("state", state);
        if (nbt != null) block.put("nbt", nbt.copy());
        return block;
    }

    // ---- .schem ----

    private static final int W = 4, H = 2, L = 3;
    private static final int AIR = 0, STONE_ID = 1, STAIRS_ID = 2, SIGN_ID = 3, GOLD_ID = 129;

    /** The 4×2×3 building shared by both Sponge versions: palette ids up to 129 so one id takes two varint bytes. */
    private static int[] spongeBlocks() {
        var ids = new int[W * H * L];
        for (int z = 0; z < L; z++) for (int x = 0; x < W; x++) ids[index(x, 0, z)] = STONE_ID;
        ids[index(0, 0, 0)] = STAIRS_ID;
        ids[index(3, 0, 2)] = GOLD_ID;
        ids[index(1, 1, 1)] = SIGN_ID;
        return ids;
    }
    private static int index(int x, int y, int z) { return x + z * W + y * W * L; }

    private static CompoundTag spongePalette() {
        var palette = new CompoundTag();
        palette.putInt("minecraft:air", AIR);
        palette.putInt(STONE, STONE_ID);
        palette.putInt(STAIRS, STAIRS_ID);
        palette.putInt(SIGN, SIGN_ID);
        for (int i = 4; i < GOLD_ID; i++) palette.putInt("hardrush:filler_" + i, i);
        palette.putInt(GOLD, GOLD_ID);
        return palette;
    }

    public static byte[] varints(int[] values) {
        var out = new java.io.ByteArrayOutputStream();
        for (int value : values) {
            while ((value & -128) != 0) {
                out.write(value & 127 | 128);
                value >>>= 7;
            }
            out.write(value);
        }
        return out.toByteArray();
    }

    private static Expected spongeExpected() {
        var states = new LinkedHashMap<Cell, String>();
        for (int z = 0; z < L; z++) for (int x = 0; x < W; x++) states.put(new Cell(x, 0, z), STONE);
        states.put(new Cell(0, 0, 0), STAIRS);
        states.put(new Cell(3, 0, 2), GOLD);
        states.put(new Cell(1, 1, 1), SIGN);
        return new Expected(W, H, L, states, Map.of(new Cell(1, 1, 1), signEntity("sponge")));
    }

    public static Sample schemV2() {
        var sign = signEntity("sponge");
        sign.remove("id");
        sign.putString("Id", "minecraft:sign");
        sign.putIntArray("Pos", new int[]{1, 1, 1});
        var entities = new ListTag();
        entities.add(sign);
        var root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", DATA_VERSION);
        root.putShort("Width", (short) W);
        root.putShort("Height", (short) H);
        root.putShort("Length", (short) L);
        root.put("Palette", spongePalette());
        root.putInt("PaletteMax", GOLD_ID + 1);
        root.putByteArray("BlockData", varints(spongeBlocks()));
        root.put("BlockEntities", entities);
        root.putIntArray("Offset", new int[]{-5, 7, 9});
        return new Sample(root, spongeExpected());
    }

    public static Sample schemV3() {
        var data = signEntity("sponge");
        data.remove("id");
        var entity = new CompoundTag();
        entity.putString("Id", "minecraft:sign");
        entity.putIntArray("Pos", new int[]{1, 1, 1});
        entity.put("Data", data);
        var entities = new ListTag();
        entities.add(entity);
        var blocks = new CompoundTag();
        blocks.put("Palette", spongePalette());
        blocks.putByteArray("Data", varints(spongeBlocks()));
        blocks.put("BlockEntities", entities);
        var schematic = new CompoundTag();
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", DATA_VERSION);
        schematic.putShort("Width", (short) W);
        schematic.putShort("Height", (short) H);
        schematic.putShort("Length", (short) L);
        schematic.put("Blocks", blocks);
        var root = new CompoundTag();
        root.put("Schematic", schematic);
        return new Sample(root, spongeExpected());
    }

    // ---- a building with a manifest (design “建筑清单与大厅包构建” 1.7) ----

    /** As external tools export fences: no connection properties set. */
    public static final String LOOSE_FENCE = "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]";
    public static final int MANIFEST_W = 7, MANIFEST_H = 3, MANIFEST_L = 5;
    /** Where the building's minimum corner is in {@link #MANIFEST}'s frame. */
    public static final Cell MANIFEST_ORIGIN = new Cell(10, 0, 20);

    /**
     * A 7×3×5 Sponge v2 building, local coordinates: a stone floor with a 2×2 lime wool patch at x 0–1, z 0–1; three
     * loose oak fences at (2–4, 1, 4); a gold block at (3, 1, 1); tinted glass at (6, 1, 3) under stone; an oak sign
     * at (5, 1, 1); a torch floating at (6, 2, 0).
     */
    public static Sample manifestBuilding() {
        var states = new LinkedHashMap<Cell, String>();
        for (int z = 0; z < MANIFEST_L; z++) for (int x = 0; x < MANIFEST_W; x++)
            states.put(new Cell(x, 0, z), x < 2 && z < 2 ? "minecraft:lime_wool" : STONE);
        for (int x = 2; x <= 4; x++) states.put(new Cell(x, 1, 4), LOOSE_FENCE);
        states.put(new Cell(3, 1, 1), GOLD);
        states.put(new Cell(6, 1, 3), "minecraft:tinted_glass");
        states.put(new Cell(6, 2, 3), STONE);
        states.put(new Cell(5, 1, 1), SIGN);
        states.put(new Cell(6, 2, 0), "minecraft:torch");
        var entities = Map.of(new Cell(5, 1, 1), signEntity("before"));
        var palette = new CompoundTag();
        palette.putInt("minecraft:air", 0);
        var ids = new int[MANIFEST_W * MANIFEST_H * MANIFEST_L];
        for (var entry : states.entrySet()) {
            var c = entry.getKey();
            if (!palette.contains(entry.getValue())) palette.putInt(entry.getValue(), palette.size());
            ids[c.x() + c.z() * MANIFEST_W + c.y() * MANIFEST_W * MANIFEST_L] = palette.getIntOr(entry.getValue(), 0);
        }
        var blockEntities = new ListTag();
        for (var entry : entities.entrySet()) {
            var tag = entry.getValue().copy();
            tag.remove("id");
            tag.putString("Id", entry.getValue().getStringOr("id", ""));
            tag.putIntArray("Pos", new int[]{entry.getKey().x(), entry.getKey().y(), entry.getKey().z()});
            blockEntities.add(tag);
        }
        var root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", DATA_VERSION);
        root.putShort("Width", (short) MANIFEST_W);
        root.putShort("Height", (short) MANIFEST_H);
        root.putShort("Length", (short) MANIFEST_L);
        root.put("Palette", palette);
        root.putInt("PaletteMax", palette.size());
        root.putByteArray("BlockData", varints(ids));
        root.put("BlockEntities", blockEntities);
        return new Sample(root, new Expected(MANIFEST_W, MANIFEST_H, MANIFEST_L, states, entities));
    }

    /**
     * The manifest for {@link #manifestBuilding()}, one marker of every binding form, in a frame where the building's
     * minimum corner is {@link #MANIFEST_ORIGIN}: manifest cell = local cell + (10, 0, 20). The second sign entry names
     * the gold block, which is not a sign.
     */
    public static final String MANIFEST = """
            {
              "format": 1,
              "origin": [10, 0, 20],
              "update_shapes": true,
              "markers": [
                {"function": "assembly", "as": "zone", "shape": "flood", "at": [10, 0, 20]},
                {"function": "courtyard", "as": "zone", "shape": "box", "at": [15, 1, 23], "box": [1, 1, 1]},
                {"function": "landing_central", "as": "point", "at": [13, 1, 22], "yaw": 180},
                {"function": "role_zombie", "as": "model", "at": [11, 1, 23], "yaw": 90},
                {"function": "rules", "as": "click", "at": [13, 1, 21]},
                {"function": "altar_light", "as": "block", "at": [16, 1, 23]},
                {"function": "status", "as": "text", "at": [15, 1, 21]}
              ],
              "signs": [
                {"at": [15, 1, 21], "front": ["清单写的字", "第二行"], "back": []},
                {"at": [13, 1, 21], "front": ["不是告示牌"]}
              ]
            }
            """;

    // ---- .litematic ----

    private static final List<String> A_PALETTE = List.of("minecraft:air", STONE, STAIRS, SIGN, GOLD);
    private static final List<String> B_PALETTE = List.of("minecraft:air", STONE, STAIRS);

    /** Region A (4×3×2 at 10,5,-3): 24 entries of 3 bits, so entry 21 spans two longs. */
    public static int aValue(int i) { return (i * 3 + 1) % 5; }
    /** Region B (Size -2,1,-3 at 15,6,1, which really spans 14..15, 6, -1..1): 6 entries of 2 bits. */
    public static int bValue(int i) { return (i + 1) % 3; }

    public static long[] pack(int[] values, int bits) {
        var out = new long[(values.length * bits + 63) / 64];
        for (int i = 0; i < values.length; i++) {
            for (int b = 0; b < bits; b++) {
                if ((values[i] >> b & 1) == 0) continue;
                long p = (long) i * bits + b;
                out[(int) (p >> 6)] |= 1L << (p & 63);
            }
        }
        return out;
    }

    private static ListTag palette(List<String> states) {
        var list = new ListTag();
        for (var state : states) list.add(stateTag(state));
        return list;
    }

    private static CompoundTag xyz(int x, int y, int z) {
        var tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    public static Sample litematic() {
        var states = new LinkedHashMap<Cell, String>();
        var blockEntities = new LinkedHashMap<Cell, CompoundTag>();
        var aValues = new int[24];
        for (int i = 0; i < 24; i++) {
            aValues[i] = aValue(i);
            if (aValues[i] != 0) states.put(new Cell(i % 4, i / 8, i / 4 % 2), A_PALETTE.get(aValues[i]));
        }
        // The first entry holding the sign state (value 3) is i = 4: (4 * 3 + 1) % 5 = 3.
        int signAt = 4;
        var signCell = new Cell(signAt % 4, signAt / 8, signAt / 4 % 2);
        blockEntities.put(signCell, signEntity("litematica"));

        var regionA = new CompoundTag();
        regionA.put("Position", xyz(10, 5, -3));
        regionA.put("Size", xyz(4, 3, 2));
        regionA.put("BlockStatePalette", palette(A_PALETTE));
        regionA.putLongArray("BlockStates", pack(aValues, 3));
        var tileEntity = signEntity("litematica");
        tileEntity.putInt("x", signCell.x());
        tileEntity.putInt("y", signCell.y());
        tileEntity.putInt("z", signCell.z());
        var tileEntities = new ListTag();
        tileEntities.add(tileEntity);
        regionA.put("TileEntities", tileEntities);
        regionA.put("Entities", new ListTag());

        var bValues = new int[6];
        // B spans x 14..15, y 6, z -1..1 absolute; relative to the global minimum (10,5,-3): x 4..5, y 1, z 2..4.
        for (int i = 0; i < 6; i++) {
            bValues[i] = bValue(i);
            if (bValues[i] != 0) states.put(new Cell(4 + i % 2, 1, 2 + i / 2), B_PALETTE.get(bValues[i]));
        }
        var regionB = new CompoundTag();
        regionB.put("Position", xyz(15, 6, 1));
        regionB.put("Size", xyz(-2, 1, -3));
        regionB.put("BlockStatePalette", palette(B_PALETTE));
        regionB.putLongArray("BlockStates", pack(bValues, 2));
        var oneEntity = new ListTag();
        oneEntity.add(new CompoundTag());
        regionB.put("Entities", oneEntity);

        var regions = new CompoundTag();
        regions.put("Region A", regionA);
        regions.put("Region B", regionB);
        var root = new CompoundTag();
        root.putInt("Version", 6);
        root.putInt("MinecraftDataVersion", DATA_VERSION);
        root.put("Regions", regions);
        return new Sample(root, new Expected(6, 3, 5, states, blockEntities));
    }
}
