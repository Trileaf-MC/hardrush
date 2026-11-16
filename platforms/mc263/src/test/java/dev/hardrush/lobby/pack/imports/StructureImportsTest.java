package dev.hardrush.lobby.pack.imports;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.lobby.pack.PackException;
import dev.hardrush.lobby.pack.TileKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureImportsTest {
    static final ImportLimits LIMITS = new ImportLimits(256, 4000);

    @TempDir Path dir;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
    }

    /** Every non-air block of the tiles, at world position = origin + tile origin + local pos. */
    static Map<Cell, String> states(Map<TileKey, CompoundTag> tiles, Cell origin) {
        var out = new TreeMap<Cell, String>(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::z).thenComparingInt(Cell::x));
        forEachBlock(tiles, origin, (cell, tile, block) -> {
            var palette = tile.getListOrEmpty("palette");
            out.put(cell, StateEntry.fromTag(palette.getCompoundOrEmpty(block.getIntOr("state", -1))).key);
        });
        return out;
    }

    static Map<Cell, CompoundTag> blockEntities(Map<TileKey, CompoundTag> tiles, Cell origin) {
        var out = new HashMap<Cell, CompoundTag>();
        forEachBlock(tiles, origin, (cell, tile, block) -> block.getCompound("nbt").ifPresent(nbt -> out.put(cell, nbt)));
        return out;
    }

    interface BlockVisitor { void visit(Cell cell, CompoundTag tile, CompoundTag block); }

    static void forEachBlock(Map<TileKey, CompoundTag> tiles, Cell origin, BlockVisitor visitor) {
        tiles.forEach((key, tile) -> {
            assertEquals(48, tile.getListOrEmpty("size").getIntOr(0, 0));
            assertTrue(tile.getListOrEmpty("entities").isEmpty());
            for (var tag : tile.getListOrEmpty("blocks")) {
                var block = (CompoundTag) tag;
                var pos = block.getListOrEmpty("pos");
                for (int i = 0; i < 3; i++) assertTrue(pos.getIntOr(i, -1) >= 0 && pos.getIntOr(i, 99) < 48);
                visitor.visit(new Cell(origin.x() + key.originX() + pos.getIntOr(0, 0), origin.y() + key.originY() + pos.getIntOr(1, 0),
                        origin.z() + key.originZ() + pos.getIntOr(2, 0)), tile, block);
            }
        });
    }

    private ImportedStructure convert(String name, SampleFiles.Sample sample) throws Exception {
        return StructureImports.read(SampleFiles.write(dir, name, sample.tag()), LIMITS);
    }

    private static void assertConverted(SampleFiles.Sample sample, ImportedStructure result) {
        var expected = sample.expected();
        assertEquals(SampleFiles.DATA_VERSION, result.dataVersion());
        assertEquals(List.of(expected.sx(), expected.sy(), expected.sz()), List.of(result.sizeX(), result.sizeY(), result.sizeZ()));
        assertEquals(sorted(expected.states()), states(result.tiles(), new Cell(0, 0, 0)));
        assertEquals(expected.blockEntities(), blockEntities(result.tiles(), new Cell(0, 0, 0)));
        for (var tile : result.tiles().values()) assertEquals(SampleFiles.DATA_VERSION, tile.getIntOr("DataVersion", 0));
    }

    private static Map<Cell, String> sorted(Map<Cell, String> in) {
        var out = new TreeMap<Cell, String>(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::z).thenComparingInt(Cell::x));
        out.putAll(in);
        return out;
    }

    @Test
    void structureTemplate() throws Exception {
        var sample = SampleFiles.nbt();
        var result = convert("house.nbt", sample);
        assertConverted(sample, result);
        assertEquals(List.of("已忽略 1 个实体"), result.notes());
    }

    @Test
    void structureTemplateTakesFirstOfSeveralPalettes() throws Exception {
        var sample = SampleFiles.nbt();
        var palettes = new ListTag();
        palettes.add(sample.tag().getListOrEmpty("palette"));
        var other = new ListTag();
        other.add(StateEntry.of("minecraft:dirt", Map.of()).tag);
        palettes.add(other);
        sample.tag().remove("palette");
        sample.tag().put("palettes", palettes);
        assertConverted(sample, convert("multi.nbt", sample));
    }

    @Test
    void spongeV2() throws Exception {
        var sample = SampleFiles.schemV2();
        var result = convert("v2.schem", sample);
        assertConverted(sample, result);
        assertTrue(result.notes().isEmpty());
    }

    @Test
    void spongeV3() throws Exception {
        var sample = SampleFiles.schemV3();
        assertConverted(sample, convert("v3.schem", sample));
    }

    @Test
    void spongeDimensionsAreUnsigned() throws Exception {
        var sample = SampleFiles.schemV2();
        sample.tag().putShort("Width", (short) 0xFFFF);
        assertRejected("wide.schem", sample.tag(), "水平尺寸 65535");
    }

    @Test
    void litematicWithTwoRegionsAndNegativeSize() throws Exception {
        var sample = SampleFiles.litematic();
        var result = convert("two.litematic", sample);
        assertConverted(sample, result);
        assertEquals(List.of("已忽略 1 个实体"), result.notes());
    }

    @Test
    void litematicOverlapLaterRegionWins() throws Exception {
        var sample = SampleFiles.litematic();
        // Move B onto A's first row: Position (13,5,-2), Size (-2,1,-1) covers x 12..13, y 5, z -2.
        var regions = sample.tag().getCompoundOrEmpty("Regions");
        var b = regions.getCompoundOrEmpty("Region B");
        b.put("Position", pos(13, 5, -2));
        b.put("Size", pos(-2, 1, -1));
        var overlapped = new int[]{2, 2};
        b.putLongArray("BlockStates", SampleFiles.pack(overlapped, 2));
        var result = StructureImports.read(SampleFiles.write(dir, "overlap.litematic", sample.tag()), LIMITS);
        // A cells (2,0,1) and (3,0,1) lie at x 12..13 of z -2: both became stairs, replacing A's values there.
        var states = states(result.tiles(), new Cell(0, 0, 0));
        assertEquals(SampleFiles.STAIRS, states.get(new Cell(2, 0, 1)));
        assertEquals(SampleFiles.STAIRS, states.get(new Cell(3, 0, 1)));
        assertTrue(result.notes().stream().anyMatch(n -> n.startsWith("区域有重叠")), result.notes().toString());
    }

    private static CompoundTag pos(int x, int y, int z) {
        var tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    @Test
    void litematicValuesStraddleLongBoundaries() {
        // Entry 21 of region A sits at bits 63..65 and its value is 4 = 0b100: only the second long holds a set bit.
        assertEquals(4, SampleFiles.aValue(21));
        var longs = SampleFiles.pack(java.util.stream.IntStream.range(0, 24).map(SampleFiles::aValue).toArray(), 3);
        assertEquals(2, longs.length);
        assertEquals(0L, longs[0] >>> 63);
        assertEquals(2L, longs[1] & 3);
    }

    @Test
    void placedAtNegativeCoordinatesRecutsTiles() throws Exception {
        var sample = SampleFiles.schemV2();
        var result = convert("place.schem", sample);
        var origin = new Cell(-50, 60, -1);
        var placed = result.placedAt(origin.x(), origin.y(), origin.z());

        var shifted = new HashMap<Cell, String>();
        sample.expected().states().forEach((c, s) -> shifted.put(new Cell(c.x() + origin.x(), c.y() + origin.y(), c.z() + origin.z()), s));
        assertEquals(sorted(shifted), states(placed, new Cell(0, 0, 0)));
        var entities = new HashMap<Cell, CompoundTag>();
        sample.expected().blockEntities().forEach((c, n) -> entities.put(new Cell(c.x() + origin.x(), c.y() + origin.y(), c.z() + origin.z()), n));
        assertEquals(entities, blockEntities(placed, new Cell(0, 0, 0)));

        // x -50..-47 straddles tiles -2 (up to -49) and -1; z -1..1 straddles -1 and 0; y 60..61 is tile 1.
        assertEquals(Set.of(new TileKey(-2, 1, -1), new TileKey(-2, 1, 0), new TileKey(-1, 1, -1), new TileKey(-1, 1, 0)), placed.keySet());
        for (var tile : placed.values()) assertEquals(SampleFiles.DATA_VERSION, tile.getIntOr("DataVersion", 0));
        // An unused palette entry never reaches a tile: each tile palette holds only states used in it.
        for (var tile : placed.values()) assertTrue(tile.getListOrEmpty("palette").size() <= 4);
    }

    @Test
    void airAndVoidAreNotWritten() throws Exception {
        var sample = SampleFiles.nbt();
        var result = convert("air.nbt", sample);
        var tile = result.tiles().get(new TileKey(0, 0, 0));
        assertEquals(3, tile.getListOrEmpty("blocks").size());
    }

    // ---- rejections ----

    private void assertRejected(String name, CompoundTag tag, String messagePart) throws Exception {
        var file = SampleFiles.write(dir, name, tag);
        var e = assertThrows(PackException.class, () -> StructureImports.read(file, LIMITS));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    @Test
    void truncatedGzipIsRejected() throws Exception {
        var file = SampleFiles.write(dir, "cut.schem", SampleFiles.schemV2().tag());
        var bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length / 2));
        assertThrows(PackException.class, () -> StructureImports.read(file, LIMITS));
    }

    @Test
    void notGzipIsRejected() throws Exception {
        var file = dir.resolve("plain.nbt");
        Files.writeString(file, "this is not nbt");
        assertThrows(PackException.class, () -> StructureImports.read(file, LIMITS));
    }

    @Test
    void truncatedVarintIsRejected() throws Exception {
        var sample = SampleFiles.schemV2();
        var data = SampleFiles.varints(new int[]{0, 1, 129});
        sample.tag().putByteArray("BlockData", Arrays.copyOf(data, data.length - 1));
        assertRejected("varint.schem", sample.tag(), "截断");
    }

    @Test
    void sizeAndDataMismatchIsRejected() throws Exception {
        var sample = SampleFiles.schemV2();
        sample.tag().putByteArray("BlockData", SampleFiles.varints(new int[]{0, 1, 2}));
        assertRejected("short.schem", sample.tag(), "比尺寸少");
        var more = SampleFiles.schemV2();
        more.tag().putByteArray("BlockData", SampleFiles.varints(new int[25]));
        assertRejected("long.schem", more.tag(), "比尺寸多");
    }

    @Test
    void paletteIndexOutOfRangeIsRejected() throws Exception {
        var sample = SampleFiles.schemV2();
        var ids = new int[24];
        ids[5] = 130;
        sample.tag().putByteArray("BlockData", SampleFiles.varints(ids));
        assertRejected("palette.schem", sample.tag(), "不存在的调色板编号：130");

        var nbt = SampleFiles.nbt();
        nbt.tag().getListOrEmpty("blocks").getCompoundOrEmpty(0).putInt("state", 9);
        assertRejected("palette.nbt", nbt.tag(), "调色板项");

        var lite = SampleFiles.litematic();
        // Region B has a 3-entry palette stored in 2 bits; value 3 is representable but missing.
        lite.tag().getCompoundOrEmpty("Regions").getCompoundOrEmpty("Region B")
                .putLongArray("BlockStates", SampleFiles.pack(new int[]{3, 0, 0, 0, 0, 0}, 2));
        assertRejected("palette.litematic", lite.tag(), "调色板项：3");
    }

    @Test
    void shortLitematicArrayIsRejected() throws Exception {
        var lite = SampleFiles.litematic();
        lite.tag().getCompoundOrEmpty("Regions").getCompoundOrEmpty("Region A").putLongArray("BlockStates", new long[1]);
        assertRejected("array.litematic", lite.tag(), "方块数据太短");
    }

    @Test
    void blockOutsideSizeIsRejected() throws Exception {
        var nbt = SampleFiles.nbt();
        nbt.tag().getListOrEmpty("blocks").getCompoundOrEmpty(0).put("pos", intList(3, 0, 0));
        assertRejected("outside.nbt", nbt.tag(), "超出尺寸");
    }

    private static ListTag intList(int... values) {
        var list = new ListTag();
        for (int v : values) list.add(IntTag.valueOf(v));
        return list;
    }

    @Test
    void oversizedBuildingIsRejected() throws Exception {
        var file = SampleFiles.write(dir, "big.schem", SampleFiles.schemV2().tag());
        var e = assertThrows(PackException.class, () -> StructureImports.read(file, new ImportLimits(3, 4000)));
        assertTrue(e.getMessage().contains("水平尺寸 4×3 超过上限 3"), e.getMessage());

        var tall = SampleFiles.schemV2();
        tall.tag().putShort("Height", (short) 385);
        assertRejected("tall.schem", tall.tag(), "高度 385");
    }

    @Test
    void newerDataVersionIsRejected() throws Exception {
        var sample = SampleFiles.schemV2();
        sample.tag().putInt("DataVersion", 4001);
        assertRejected("new.schem", sample.tag(), "更新的游戏版本");
        var lite = SampleFiles.litematic();
        lite.tag().putInt("MinecraftDataVersion", 9999);
        assertRejected("new.litematic", lite.tag(), "更新的游戏版本");
    }

    @Test
    void oldAndUnknownFormatsAreRejected() throws Exception {
        var v1 = SampleFiles.schemV2();
        v1.tag().putInt("Version", 1);
        assertRejected("v1.schem", v1.tag(), "版本 1");
        var legacy = SampleFiles.write(dir, "old.schematic", new CompoundTag());
        var e = assertThrows(PackException.class, () -> StructureImports.read(legacy, LIMITS));
        assertTrue(e.getMessage().contains("WorldEdit"));
        var other = SampleFiles.write(dir, "thing.zip", new CompoundTag());
        assertThrows(PackException.class, () -> StructureImports.read(other, LIMITS));
    }

    @Test
    void badBlockStateStringIsRejected() throws Exception {
        var sample = SampleFiles.schemV2();
        var palette = sample.tag().getCompoundOrEmpty("Palette");
        palette.putInt("minecraft:oak_stairs[facing=north", 4);
        assertRejected("state.schem", sample.tag(), "方块状态");
    }
}
