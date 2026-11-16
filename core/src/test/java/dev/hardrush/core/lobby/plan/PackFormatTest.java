package dev.hardrush.core.lobby.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PackFormatTest {
    static final Box BOUNDS = new Box(new Cell(-24, 63, -16), new Cell(39, 70, 31));
    static PackManifest manifest(int format, String id, String name, List<String> authors, int version, int dataVersion, Box bounds) {
        return new PackManifest(format, id, name, authors, version, "2026-11-13T08:00:00Z", "1.0.0", dataVersion, bounds);
    }

    @Test void validManifest() {
        var m = manifest(1, "default", "默认营地", List.of("LeifWebber"), 3, 4000, BOUNDS);
        assertEquals(List.of(), m.problems(256, 4000));
        assertEquals("default-v3.hrlobby", m.fileName());
        assertEquals(List.of(), manifest(1, "a_b-9", "x".repeat(64), Collections.nCopies(32, "a"), 1, 0, BOUNDS).problems(64, 0));
    }
    @Test void invalidManifests() {
        assertEquals(1, manifest(2, "default", "n", List.of(), 1, 0, BOUNDS).problems(256, 0).size());
        for (var id : Arrays.asList("Default", "", "a b", "x".repeat(33), "a:b", null))
            assertEquals(1, manifest(1, id, "n", List.of(), 1, 0, BOUNDS).problems(256, 0).size(), String.valueOf(id));
        for (var name : Arrays.asList(null, "", "  ", "字".repeat(65)))
            assertEquals(1, manifest(1, "a", name, List.of(), 1, 0, BOUNDS).problems(256, 0).size(), String.valueOf(name));
        assertEquals(1, manifest(1, "a", "n", Collections.nCopies(33, "a"), 1, 0, BOUNDS).problems(256, 0).size());
        assertEquals(1, manifest(1, "a", "n", List.of(), 0, 0, BOUNDS).problems(256, 0).size());
        assertEquals(1, manifest(1, "a", "n", List.of(), 1, 4001, BOUNDS).problems(256, 4000).size());
        assertEquals(1, manifest(1, "a", "n", List.of(), 1, 0, BOUNDS).problems(63, 0).size());
        assertEquals(1, manifest(1, "a", "n", List.of(), 1, 0, null).problems(256, 0).size());
        assertEquals(List.of(), manifest(1, "a", "n", null, 1, 0, BOUNDS).authors());
    }
    @Test void boundsMustLieInTheWorld() {
        // int arithmetic made this box 0 wide, which passed the horizontal limit.
        var whole = new Box(new Cell(Integer.MIN_VALUE, 0, Integer.MIN_VALUE), new Cell(Integer.MAX_VALUE, 0, Integer.MAX_VALUE));
        assertEquals(1L << 32, whole.sizeX());
        assertEquals(1, manifest(1, "a", "n", List.of(), 1, 0, whole).problems(256, 0).size());
        for (var outside : List.of(new Box(new Cell(30_000_000, 0, 0), new Cell(30_000_001, 0, 0)),
                new Box(new Cell(0, 0, -30_000_001), new Cell(0, 0, -30_000_000)),
                new Box(new Cell(0, -2033, 0), new Cell(0, 0, 0)), new Box(new Cell(0, 0, 0), new Cell(0, 2032, 0))))
            assertEquals(1, manifest(1, "a", "n", List.of(), 1, 0, outside).problems(256, 0).size(), outside.toString());
        for (var edge : List.of(new Box(new Cell(29_999_990, -2032, -30_000_000), new Cell(30_000_000, 2031, -29_999_990))))
            assertEquals(List.of(), manifest(1, "a", "n", List.of(), 1, 0, edge).problems(256, 0));
    }

    @Test void blockStateStrings() {
        assertEquals(new BlockStateString("minecraft:stone", Map.of()), BlockStateString.parse("stone"));
        assertEquals(new BlockStateString("minecraft:stone", Map.of()), BlockStateString.parse("minecraft:stone[]"));
        var stairs = BlockStateString.parse("minecraft:oak_stairs[facing=north,half=bottom]");
        assertEquals(List.of("facing", "half"), List.copyOf(stairs.properties().keySet()));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", stairs.format());
        assertEquals(stairs, BlockStateString.parse("  oak_stairs[ facing = north , half=bottom ] "));
        assertEquals("mymod:deco/lamp[lit=true]", BlockStateString.parse("mymod:deco/lamp[lit=true]").format());
        assertEquals("minecraft:stone", BlockStateString.parse("minecraft:stone[ ]").format());
        for (var bad : List.of("minecraft:oak_stairs[facing=north", "oak_stairs[facing=north,facing=south]", "oak_stairs[=north]",
                "oak_stairs[facing=]", "oak_stairs[facing]", "oak_stairs[facing=north,]", "Stone", "minecraft:st one", "[facing=north]",
                "oak_stairs[facing=north]x", "oak_stairs[facing=[north]]", "", "a:b:c", "oak_stairs[fac!ng=north]"))
            assertThrows(IllegalArgumentException.class, () -> BlockStateString.parse(bad), bad);
    }
}
