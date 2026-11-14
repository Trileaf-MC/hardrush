package dev.hardrush.core.lobby.plan;

import java.util.*;

/** Resolves a flood zone marker into floor cells: four-way spread on the start block's layer only. */
public final class FloodFill {
    public static final int LIMIT = 4096;
    private static final Set<String> AIRS = Set.of(BlockLookup.AIR, "minecraft:cave_air", "minecraft:void_air");

    /** {@code overflow} means the search stopped at the limit; {@code cells} then holds a partial area. */
    public record Result(Set<Cell> cells, boolean overflow) {
        public Result { cells = Set.copyOf(cells); }
    }

    /** {@code start} is the block under the marker; the materials are its own type plus {@code extraMaterials}. */
    public static Result fill(BlockLookup world, Cell start, Set<String> extraMaterials, int limit) {
        var first = world.blockId(start.x(), start.y(), start.z());
        if (AIRS.contains(first)) return new Result(Set.of(), false);
        var materials = new HashSet<>(extraMaterials);
        materials.add(first);
        materials.removeAll(AIRS);
        var cells = new LinkedHashSet<Cell>();
        var queue = new ArrayDeque<Cell>();
        cells.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            var c = queue.poll();
            for (var next : List.of(c.offset(1, 0, 0), c.offset(-1, 0, 0), c.offset(0, 0, 1), c.offset(0, 0, -1))) {
                if (cells.contains(next) || !materials.contains(world.blockId(next.x(), next.y(), next.z()))) continue;
                if (cells.size() >= limit) return new Result(cells, true);
                cells.add(next);
                queue.add(next);
            }
        }
        return new Result(cells, false);
    }
    private FloodFill() {}
}
