package dev.hardrush.hunt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The quarry's space: every open cell ({@link #open}) connected to the quarry's body cell within
 * {@link SafeDeployment#BREACH_RADIUS} blocks, explored breadth first until {@link SafeDeployment#BREACH_VISITS} cells
 * (open or not) have been looked at. Read once, from the world as it is now.
 */
final class QuarrySpace {
    /** Every open cell reached, with its number of steps from the quarry's cell. */
    private final Map<BlockPos, Integer> steps = new HashMap<>();
    /** The reached cells whose neighbours were looked at, in breadth-first order; the rest were cut off by the visit cap. */
    private final List<BlockPos> explored = new ArrayList<>();

    private QuarrySpace() {}

    static QuarrySpace explore(ServerLevel level, Vec3 position) {
        var space = new QuarrySpace();
        // The body's cell, like MonsterDiggable.bodyCell: on soul sand or a slab the feet sink into the floor block.
        var start = BlockPos.containing(position.x, position.y + .5, position.z);
        if (!open(level, start)) return space;
        var visited = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        visited.add(start); queue.add(start); space.steps.put(start, 0);
        while (!queue.isEmpty() && visited.size() < SafeDeployment.BREACH_VISITS) {
            var cell = queue.poll();
            space.explored.add(cell);
            int next = space.steps.get(cell) + 1;
            for (var side : Direction.values()) {
                var neighbour = cell.relative(side);
                if (neighbour.distToCenterSqr(position) > SafeDeployment.BREACH_RADIUS * SafeDeployment.BREACH_RADIUS
                        || !visited.add(neighbour) || !open(level, neighbour)) continue;
                queue.add(neighbour);
                space.steps.put(neighbour, next);
            }
        }
        return space;
    }

    /** Air a body passes through: no collision, no fluid, already loaded. */
    static boolean open(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getFluidState(pos).isEmpty();
    }

    boolean contains(BlockPos pos) { return steps.containsKey(pos); }
    /** Steps from the quarry's cell, or -1 outside the space. */
    int steps(BlockPos pos) { return steps.getOrDefault(pos, -1); }
    /** The explored cells, nearest first (breadth-first order). */
    List<BlockPos> cells() { return java.util.Collections.unmodifiableList(explored); }
}
