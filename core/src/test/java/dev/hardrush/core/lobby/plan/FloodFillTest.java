package dev.hardrush.core.lobby.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class FloodFillTest {
    final Map<Cell, String> blocks = new HashMap<>();
    final BlockLookup world = (x, y, z) -> blocks.getOrDefault(new Cell(x, y, z), BlockLookup.AIR);

    void disc(int radius, int y, String id) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            if (x * x + z * z <= radius * radius) blocks.put(new Cell(x, y, z), id);
    }

    @Test void woolDiscStopsAtTheSurroundingFloor() {
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) blocks.put(new Cell(x, 64, z), "minecraft:stone");
        disc(3, 64, "minecraft:lime_wool");
        var result = FloodFill.fill(world, new Cell(0, 64, 0), Set.of(), FloodFill.LIMIT);
        assertFalse(result.overflow());
        assertEquals(blocks.values().stream().filter("minecraft:lime_wool"::equals).count(), result.cells().size());
        assertTrue(result.cells().stream().allMatch(c -> blocks.get(c).equals("minecraft:lime_wool")));
    }
    @Test void extraMaterialsJoinTheArea() {
        disc(3, 64, "minecraft:lime_wool");
        blocks.put(new Cell(0, 64, 0), "minecraft:gold_block");
        blocks.put(new Cell(4, 64, 0), "minecraft:green_wool");
        var onlyStart = FloodFill.fill(world, new Cell(1, 64, 0), Set.of(), FloodFill.LIMIT);
        assertFalse(onlyStart.cells().contains(new Cell(0, 64, 0)));
        var both = FloodFill.fill(world, new Cell(1, 64, 0), Set.of("minecraft:gold_block", "minecraft:green_wool", BlockLookup.AIR), FloodFill.LIMIT);
        assertTrue(both.cells().contains(new Cell(0, 64, 0)));
        assertTrue(both.cells().contains(new Cell(4, 64, 0)));
        assertEquals(30, both.cells().size());
    }
    @Test void overflowStopsAtTheLimit() {
        disc(10, 64, "minecraft:lime_wool");
        var result = FloodFill.fill(world, new Cell(0, 64, 0), Set.of(), 50);
        assertTrue(result.overflow());
        assertEquals(50, result.cells().size());
    }
    @Test void otherLayersDoNotConnect() {
        blocks.put(new Cell(0, 64, 0), "minecraft:lime_wool");
        blocks.put(new Cell(1, 65, 0), "minecraft:lime_wool");
        blocks.put(new Cell(2, 64, 0), "minecraft:lime_wool");
        assertEquals(Set.of(new Cell(0, 64, 0)), FloodFill.fill(world, new Cell(0, 64, 0), Set.of(), FloodFill.LIMIT).cells());
    }
    @Test void airStartIsEmpty() {
        var result = FloodFill.fill(world, new Cell(0, 64, 0), Set.of("minecraft:lime_wool"), FloodFill.LIMIT);
        assertTrue(result.cells().isEmpty());
        assertFalse(result.overflow());
    }
}
