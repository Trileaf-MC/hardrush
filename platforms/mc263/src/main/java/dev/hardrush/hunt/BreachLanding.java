package dev.hardrush.hunt;

import static dev.hardrush.hunt.SafeDeployment.EPSILON;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.monster.MonsterDiggable;
import dev.hardrush.protection.ProtectionRuntime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A landing one dig away from the quarry's space ({@link QuarrySpace}): beside it behind one wall, or on top of it above
 * one floor. Either way the stance is a pocket of plain ground hollowed only at departure ({@link HuntBurrows#carve}),
 * or open air the monster lands on directly, where no human may see it. Candidates come from one scan of the quarry's
 * space and are ranked by cheap terms; the best few are then checked for sight, and the first unseen one is taken.
 */
public final class BreachLanding implements LandingStrategy {
    private final ServerLevel level;
    private final ServerPlayer player;
    private final ArrayDeque<Candidate> queue = new ArrayDeque<>();
    private Candidate best;

    /**
     * One breach: the stance's feet cell; the exit, a cell of the quarry's space; through its ceiling ({@code above}) or
     * through a wall beside it; and whether the stance is a pocket of plain ground ({@code pocket}) or open air.
     */
    public record Spot(BlockPos stance, BlockPos exit, boolean above, boolean pocket) {
        public Spot { stance = stance.immutable(); exit = exit.immutable(); }
        /** The landing's feet: the floor center of the stance. */
        public Vec3 point() { return Vec3.atBottomCenterOf(stance); }
        /** What is dug through: the wall at foot and head height, or the floor under the stance. */
        public List<BlockPos> barrier() {
            if (above) return List.of(stance.below());
            var wall = new BlockPos((stance.getX() + exit.getX()) / 2, exit.getY(), (stance.getZ() + exit.getZ()) / 2);
            return List.of(wall, wall.above());
        }
        public SafeDeployment.Landing landing() { return pocket ? SafeDeployment.Landing.BURROW : SafeDeployment.Landing.BREACH; }
        static Spot of(Candidate candidate) {
            return new Spot(BlockPos.containing(candidate.point), candidate.exit, candidate.above,
                    candidate.landing == SafeDeployment.Landing.BURROW);
        }
    }

    BreachLanding(ServerLevel level, ServerPlayer player) { this.level = level; this.player = player; }

    /** Only where the game rule lets mobs grief. */
    @Override
    public boolean applies(Sweep sweep) { return HuntBurrows.allowed(sweep.level); }

    /** The whole scan of the quarry's space: its own tick, like any stage after the first. */
    @Override
    public void begin(Sweep sweep) {
        best = null;
        queue.clear(); queue.addAll(candidates(sweep));
    }

    /** Sight for the best few, in order of score; the first one nobody sees is this sweep's breach. */
    @Override
    public boolean step(Sweep sweep, long tickStartedNanos) {
        while (!queue.isEmpty()) {
            var candidate = queue.poll();
            sweep.judged();
            if (candidate.landing == SafeDeployment.Landing.BURROW
                    || unseen(sweep.region, candidate.point, sweep.quarry.position(), sweep.humans())) {
                best = candidate; break;
            }
        }
        queue.clear();
        return true;
    }

    @Override
    public Candidate best() { return best; }

    @Override
    public boolean revalidate(Candidate candidate, Vec3 target, List<Vec3> humans) {
        return canBreachAt(level, player, Spot.of(candidate), target, humans);
    }

    /**
     * From every cell of the quarry's space within {@link SafeDeployment#BREACH_STEPS} steps, the stance beyond each
     * wall beside it and the one above its ceiling; the best {@link SafeDeployment#BURROW_CANDIDATES} that pass every
     * rule but sight. A creeper, which cannot dig itself out, only lands in the open.
     */
    private List<Candidate> candidates(Sweep sweep) {
        var space = sweep.space();
        var found = new ArrayList<Candidate>();
        boolean creeper = sweep.kind == MonsterRole.CREEPER;
        for (var cell : space.cells()) {
            int steps = space.steps(cell);
            // Breadth first: every later cell is at least as far.
            if (steps > SafeDeployment.BREACH_STEPS) break;
            var spots = new ArrayList<Spot>(5);
            if (!sweep.empty(cell.below()))
                for (var side : Direction.Plane.HORIZONTAL) spots.add(spot(cell.relative(side, 2), cell, false));
            if (!QuarrySpace.open(level, cell.above())) spots.add(spot(cell.above(2), cell, true));
            for (var spot : spots) {
                if (creeper && spot.pocket() || !fits(level, player, spot, space, sweep.quarry.position(), sweep.humans())) continue;
                var candidate = new Candidate(spot.point(), false);
                candidate.landing = spot.landing();
                candidate.exit = spot.exit();
                candidate.above = spot.above();
                candidate.crawlway = crawlway(level, spot);
                candidate.score = score(sweep, spot, steps);
                found.add(candidate);
            }
        }
        found.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.score).reversed());
        return found.subList(0, Math.min(SafeDeployment.BURROW_CANDIDATES, found.size()));
    }

    /** Plain ground at the stance can only be a pocket; anything else can only be open air. */
    private Spot spot(BlockPos stance, BlockPos exit, boolean above) {
        return new Spot(stance, exit, above, MonsterDiggable.natural(level, stance));
    }

    /** The quarry lies in a passage one block high: its exit has a solid head (beside) or a solid floor right below (above). */
    private static boolean crawlway(ServerLevel level, Spot spot) {
        var pos = spot.above() ? spot.exit().below() : spot.exit().above();
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Close but not touching; in the way it is tunnelling, or overhead while it lies still; along its own footprints,
     * in the dark, quick to dig and near the quarry's own cell.
     */
    private double score(Sweep sweep, Spot spot, int steps) {
        var quarry = sweep.quarry;
        var point = spot.point();
        double distance = point.distanceTo(quarry.position());
        double ideal = spot.above() ? SafeDeployment.IDEAL_ABOVE : SafeDeployment.IDEAL_BURROW;
        double score = 1 - Math.min(1, Math.abs(distance - ideal) / 6.0);
        boolean digging = quarry.digging().horizontalDistanceSqr() > EPSILON;
        if (!spot.above()) {
            var away = new Vec3(point.x - quarry.position().x, 0, point.z - quarry.position().z);
            if (away.lengthSqr() > EPSILON && away.normalize().dot(quarry.heading()) >= Math.cos(Math.PI / 4))
                score += digging ? 1.2 : .8;
        } else if (quarry.crawling() || !digging) score += .8;
        if (spot.pocket()) score += .2;
        var exit = Vec3.atBottomCenterOf(spot.exit());
        if (quarry.trail().stream().anyMatch(step -> step.distanceToSqr(exit) <= 2.25)) score += .3;
        score += .3 * (1 - level.getMaxLocalRawBrightness(spot.exit()) / 15.0);
        int cap = MonsterDiggable.capTicks(player), ticks = 0;
        for (var cell : spot.barrier()) ticks = Math.max(ticks, MonsterDiggable.ticks(level, cell, level.getBlockState(cell), cap));
        score -= .5 * Math.min(1, ticks / 300.0);
        score -= .03 * steps;
        return score;
    }

    /**
     * A breach, judged as it stands now against the quarry's space explored afresh from {@code target}: every rule of
     * {@link #fits}, and for open air, no human sees the stance.
     */
    public static boolean canBreachAt(ServerLevel level, ServerPlayer player, Spot spot, Vec3 target, List<Vec3> humans) {
        if (ProtectionRuntime.protectedLevel(level) || !HuntBurrows.allowed(level)) return false;
        if (!fits(level, player, spot, QuarrySpace.explore(level, target), target, humans)) return false;
        if (spot.pocket()) return true;
        int reach = (int) SafeDeployment.SIGHT_RANGE + 2;
        var region = new PathNavigationRegion(level, spot.stance().offset(-reach, -reach, -reach), spot.stance().offset(reach, reach, reach));
        return unseen(region, spot.point(), target, humans);
    }

    /**
     * Every rule of a breach but sight. Geometry: beside, the stance is two blocks from an exit with a floor, and the wall
     * (two blocks, foot and head height) lies between; above, the stance stands on the exit's ceiling, and the exit's
     * column of air is at most two blocks high over a safe floor. The wall or ceiling is fully solid, diggable by a
     * monster's hands, with no fluid around it. A pocket is plain ground sealed by opaque ground on every side; open air
     * lies outside the quarry's space and admits the hunter's body. Distances: two to ten blocks from the quarry and two
     * from every human, but four from both for open air beside a wall.
     */
    static boolean fits(ServerLevel level, ServerPlayer player, Spot spot, QuarrySpace space, Vec3 target, List<Vec3> humans) {
        if (ProtectionRuntime.protectedLevel(level) || !HuntBurrows.allowed(level)) return false;
        var stance = spot.stance();
        var exit = spot.exit();
        if (spot.above()) {
            if (!stance.equals(exit.above(2))) return false;
        } else {
            int dx = stance.getX() - exit.getX(), dz = stance.getZ() - exit.getZ();
            if (stance.getY() != exit.getY() || Math.abs(dx) + Math.abs(dz) != 2 || dx != 0 && dz != 0) return false;
        }
        // Cheapest first: most cells of a wide cave fail on distance alone.
        var point = spot.point();
        double near = spot.above() || spot.pocket() ? SafeDeployment.BURROW_NEAR : SafeDeployment.BREACH_OPEN_NEAR;
        double distance = point.distanceTo(target);
        if (distance < near - EPSILON || distance > SafeDeployment.BURROW_FAR + EPSILON) return false;
        for (var human : humans) if (point.distanceToSqr(human) < near * near - EPSILON) return false;
        int ceiling = level.getMinY() + level.dimensionType().logicalHeight();
        if (target.y < ceiling && point.y >= ceiling) return false;
        int steps = space.steps(exit);
        if (steps < 0 || steps > SafeDeployment.BREACH_STEPS) return false;
        if (!SafeDeployment.loaded(level, new AABB(stance).minmax(new AABB(exit)).inflate(1, 3, 1))
                || !level.getWorldBorder().isWithinBounds(stance) || !level.getWorldBorder().isWithinBounds(exit)) return false;
        // The wall or ceiling: one dig of solid, diggable blocks that lets no water or lava in.
        int cap = MonsterDiggable.capTicks(player), max = MonsterDiggable.maxTicks(player);
        var barrier = spot.barrier();
        for (var cell : barrier) {
            var state = level.getBlockState(cell);
            if (!net.minecraft.world.level.block.Block.isShapeFullBlock(state.getCollisionShape(level, cell))
                    || MonsterDiggable.verdict(level, cell, cap, max) != MonsterDiggable.Verdict.DIGGABLE
                    || level.getServer().isUnderSpawnProtection(level, cell, player)) return false;
            for (var side : Direction.values()) if (!level.getFluidState(cell.relative(side)).isEmpty()) return false;
        }
        if (spot.above()) {
            // Dropping through the ceiling lands on a safe floor at most three blocks down.
            var ground = exit.below();
            if (empty(level, ground)) {
                if (!QuarrySpace.open(level, ground)) return false;
                ground = ground.below();
                if (empty(level, ground)) return false;
            }
            var floor = level.getBlockState(ground);
            if (EntityTypes.PLAYER.isBlockDangerous(floor) || !floor.getFluidState().isEmpty()) return false;
        } else {
            if (empty(level, exit.below())) return false;
            // Nothing to refill the opened wall.
            if (level.getBlockState(barrier.getLast().above()).getBlock() instanceof FallingBlock) return false;
        }
        var under = level.getBlockState(stance.below());
        if (empty(level, stance.below()) || EntityTypes.PLAYER.isBlockDangerous(under)) return false;
        var body = List.of(stance, stance.above());
        if (!spot.pocket()) {
            for (var cell : body) if (space.contains(cell)) return false;
            return SafeDeployment.canDeployAt(level, player, point);
        }
        for (var cell : body)
            if (!MonsterDiggable.natural(level.getBlockState(cell)) || level.getServer().isUnderSpawnProtection(level, cell, player)) return false;
        // Sealed: every face of the pocket meets opaque ground, so nobody sees in and nothing flows in.
        for (var cell : body) for (var side : Direction.values()) {
            var neighbour = cell.relative(side);
            if (body.contains(neighbour)) continue;
            var state = level.getBlockState(neighbour);
            if (!state.isSolidRender() || !state.getFluidState().isEmpty()) return false;
        }
        // Nothing to drop into the hollowed pocket.
        return !(level.getBlockState(stance.above(2)).getBlock() instanceof FallingBlock);
    }

    private static boolean empty(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * No human within {@link SafeDeployment#SIGHT_RANGE} sees the stance: from a crawling and a standing eye over its
     * feet, every line to the stance's feet, middle and head is blocked by what blocks the eye ({@link Sight}). The
     * quarry counts as a human too, wherever the list came from.
     */
    static boolean unseen(BlockGetter world, Vec3 point, Vec3 target, List<Vec3> humans) {
        var watchers = new ArrayList<Vec3>(humans.size() + 1);
        watchers.addAll(humans); watchers.add(target);
        double range = SafeDeployment.SIGHT_RANGE * SafeDeployment.SIGHT_RANGE;
        for (var human : watchers) {
            if (human.distanceToSqr(point) > range) continue;
            for (double eye : new double[]{.4, 1.62}) for (double aim : new double[]{.1, 1.0, 1.7}) {
                if (world.clip(new Sight(human.add(0, eye, 0), point.add(0, aim, 0))).getType() == HitResult.Type.MISS) return false;
            }
        }
        return true;
    }

    /**
     * What a look passes through: vanilla's visual shapes ({@code ClipContext.Block.VISUAL}), so glass, panes and bars
     * hide nothing and a fence's gaps show. Leaves, doors, trapdoors and fence gates are see-through in places although
     * vanilla gives them solid visual shapes, so they hide nothing either.
     */
    private static final class Sight extends ClipContext {
        Sight(Vec3 from, Vec3 to) { super(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()); }

        @Override
        public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES))
                return Shapes.empty();
            return super.getBlockShape(state, level, pos);
        }
    }
}
