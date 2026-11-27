package dev.hardrush.hunt;

import dev.hardrush.core.round.MonsterRole;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * What one sweep of a {@link SafeDeployment.Search} knows: the hunter, the quarry as it stood when the sweep began, the
 * pathfinding region around it, the block it stands on and the structure it is in. The humans are the live ones of the
 * current tick, as every check of a sweep has always judged them. The quarry's space is explored on first use only.
 */
final class Sweep {
    final ServerLevel level;
    final ServerPlayer player;
    final MonsterRole kind;
    /** The quarry as it was when this sweep began; it does not follow the live quarry. */
    final SafeDeployment.Quarry quarry;
    final RandomGenerator random;
    final LongSupplier clock;
    final PathNavigationRegion region;
    /** The block the quarry stands on, the goal of every path. */
    final BlockPos goal;
    /** The listed structure the quarry is inside, or {@code null}. */
    final StructureStart lair;
    private final Walker walker;
    private final Runnable judged;
    /** Whether the search's previous land sweep walked somewhere (the first sweep counts as if it had). */
    final boolean walkedBefore;
    private List<Vec3> humans;
    private QuarrySpace space;

    Sweep(ServerLevel level, ServerPlayer player, MonsterRole kind, SafeDeployment.Quarry quarry, List<Vec3> humans,
          RandomGenerator random, LongSupplier clock, Walker walker, Runnable judged, boolean walkedBefore) {
        this.level = level; this.player = player; this.kind = kind; this.quarry = quarry; this.humans = humans;
        this.random = random; this.clock = clock; this.walker = walker; this.judged = judged; this.walkedBefore = walkedBefore;
        var center = BlockPos.containing(quarry.position());
        int reach = SafeDeployment.FALLBACK_RADIUS + SafeDeployment.FALLBACK_PATH + 8;
        region = new PathNavigationRegion(level, center.offset(-reach, -reach, -reach), center.offset(reach, reach, reach));
        goal = SafeDeployment.ground(region, quarry.position());
        lair = SafeDeployment.lair(level, center);
    }

    /** All active humans in this dimension, as of the current tick. */
    List<Vec3> humans() { return humans; }
    void humans(List<Vec3> current) { humans = Objects.requireNonNull(current); }

    /** The quarry's space, explored from the world as it is the first time it is asked for. */
    QuarrySpace space() {
        if (space == null) space = QuarrySpace.explore(level, quarry.position());
        return space;
    }

    /** Counts one candidate that passed its terrain check and was judged further, for {@link SafeDeployment.Result#candidates}. */
    void judged() { judged.run(); }

    /**
     * Judges queued candidates, at most {@link SafeDeployment#PATHS_PER_TICK} per tick and none more once the tick's
     * budget is spent; {@code true} once the queue is empty.
     */
    boolean drain(Deque<Candidate> queue, Consumer<Candidate> judge, long tickStartedNanos) {
        for (int paths = 0; !queue.isEmpty() && paths < SafeDeployment.PATHS_PER_TICK; paths++) {
            if (paths > 0 && clock.getAsLong() - tickStartedNanos >= SafeDeployment.TICK_BUDGET_NANOS) return false;
            judge.accept(queue.poll());
        }
        return queue.isEmpty();
    }

    /** Queues a landing unless its block was already offered this sweep or it is out of bounds for the quarry and humans. */
    boolean offer(List<Candidate> list, Set<BlockPos> seen, Vec3 point, boolean trail, double radius) {
        if (!seen.add(BlockPos.containing(point))
                || !SafeDeployment.allowed(level, point, quarry.position(), humans, radius, SafeDeployment.MIN_HUMAN_DISTANCE)) return false;
        list.add(new Candidate(point, trail));
        return true;
    }

    boolean empty(BlockPos pos) { return empty(level, pos); }

    /** Nothing a body collides with in this block. */
    static boolean empty(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** No block between the two points, judged on this sweep's region. */
    boolean clear(Vec3 from, Vec3 to) {
        return region.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }

    /** A vanilla walk from {@code from} toward the quarry's {@link #goal}, or {@code null} without an agent. */
    Path path(Vec3 from) { return walker.path(region, goal, from); }

    /** The pathfinding agent of a whole search: created once, on first use, and reused by every sweep. */
    static final class Walker {
        private final ServerLevel level;
        private final MonsterRole kind;
        private Mob agent;
        private PathFinder finder;

        Walker(ServerLevel level, MonsterRole kind) { this.level = level; this.kind = kind; }

        Path path(PathNavigationRegion region, BlockPos goal, Vec3 from) {
            if (agent == null) {
                var type = switch (kind) {
                    case ZOMBIE -> EntityTypes.ZOMBIE; case SKELETON -> EntityTypes.SKELETON; case CREEPER -> EntityTypes.CREEPER;
                };
                agent = type.create(level, new EntitySpawnRequest(EntitySpawnReason.COMMAND, true));
                if (agent == null) return null;
                var walker = new WalkNodeEvaluator();
                // Players open wooden doors and swim; they can neither build nor break terrain.
                walker.setCanOpenDoors(true); walker.setCanPassDoors(true); walker.setCanFloat(true);
                finder = new PathFinder(walker, SafeDeployment.MAX_VISITED_NODES);
            }
            agent.setPos(from); agent.setOnGround(true);
            return finder.findPath(region, agent, Set.of(goal), SafeDeployment.FALLBACK_PATH, 1, 1);
        }
    }
}
