package dev.hardrush.hunt;

import static dev.hardrush.hunt.SafeDeployment.EPSILON;

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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A landing one dig away from the quarry's space. For now only the burrow: a sealed pocket of plain ground behind one
 * block of wall beside an open standing cell of the quarry's space, hollowed only at departure
 * ({@link HuntBurrows#carve}). Candidates come from one scan of the quarry's space and are ranked by cheap terms; a
 * vanilla walk from the wall's open side must still reach the quarry.
 */
public final class BreachLanding implements LandingStrategy {
    private final ServerLevel level;
    private final ServerPlayer player;
    private final ArrayDeque<Candidate> queue = new ArrayDeque<>();
    private Candidate best;

    BreachLanding(ServerLevel level, ServerPlayer player) { this.level = level; this.player = player; }

    /** Only where the game rule lets mobs grief. */
    @Override
    public boolean applies(Sweep sweep) { return HuntBurrows.allowed(sweep.level); }

    @Override
    public void begin(Sweep sweep) {
        best = null;
        queue.clear(); queue.addAll(candidates(sweep));
    }

    @Override
    public boolean step(Sweep sweep, long tickStartedNanos) {
        return sweep.drain(queue, candidate -> judge(sweep, candidate), tickStartedNanos);
    }

    @Override
    public Candidate best() { return best; }

    @Override
    public boolean revalidate(Candidate candidate, Vec3 target, List<Vec3> humans) {
        return canBreachAt(level, player, candidate.point, candidate.exit, target, humans);
    }

    /**
     * From every explored standing cell of the quarry's space, one cell of plain ground sideways and a pocket right
     * behind it; the best {@link SafeDeployment#BURROW_CANDIDATES} by cheap terms. Paths come later.
     */
    private List<Candidate> candidates(Sweep sweep) {
        var found = new ArrayList<Candidate>();
        for (var cell : sweep.space().cells()) {
            if (!QuarrySpace.open(level, cell.above()) || QuarrySpace.open(level, cell.below())) continue;
            for (var side : Direction.Plane.HORIZONTAL) {
                if (!MonsterDiggable.natural(level.getBlockState(cell.relative(side)))) continue;
                var pocket = cell.relative(side, 2);
                var point = Vec3.atBottomCenterOf(pocket);
                if (!canBreachAt(level, player, point, cell, sweep.quarry.position(), sweep.humans())) continue;
                var candidate = new Candidate(point, false);
                candidate.landing = SafeDeployment.Landing.BURROW;
                candidate.exit = cell.immutable();
                candidate.score = score(sweep, candidate);
                found.add(candidate);
            }
        }
        found.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.score).reversed());
        return found.subList(0, Math.min(SafeDeployment.BURROW_CANDIDATES, found.size()));
    }

    /** Close but not touching, in the way it is tunnelling, along its own footprints, in the dark. */
    private double score(Sweep sweep, Candidate candidate) {
        var quarry = sweep.quarry;
        var point = candidate.point;
        double distance = point.distanceTo(quarry.position());
        double score = 1 - Math.min(1, Math.abs(distance - SafeDeployment.IDEAL_BURROW) / 6.0);
        var away = new Vec3(point.x - quarry.position().x, 0, point.z - quarry.position().z);
        if (away.lengthSqr() > EPSILON && away.normalize().dot(quarry.heading()) >= Math.cos(Math.PI / 4)) score += .8;
        var exit = Vec3.atBottomCenterOf(candidate.exit);
        if (quarry.trail().stream().anyMatch(step -> step.distanceToSqr(exit) <= 2.25)) score += .3;
        score += .3 * (1 - level.getMaxLocalRawBrightness(candidate.exit) / 15.0);
        return score;
    }

    private void judge(Sweep sweep, Candidate candidate) {
        // The wall is opened by digging; from there on it is an ordinary walk to the quarry.
        if (!canBreachAt(level, player, candidate.point, candidate.exit, sweep.quarry.position(), sweep.humans())) return;
        sweep.judged();
        var path = sweep.path(Vec3.atBottomCenterOf(candidate.exit));
        if (path == null || !path.canReach() || SafeDeployment.length(path) > SafeDeployment.MAX_PATH + EPSILON) return;
        if (best == null || candidate.score > best.score) best = candidate;
    }

    /**
     * A burrow, judged as it stands now: a pocket of two plain-ground blocks ({@link MonsterDiggable}) that sealed
     * opaque ground hides on every side, on a safe floor, under nothing that would fall in; one block of plain ground
     * between it and {@code exit}, an open standing cell of the quarry's space; no fluid that digging would let in.
     * {@code point} is the pocket's floor center. The pocket is still solid here; it is hollowed only at departure.
     */
    public static boolean canBreachAt(ServerLevel level, ServerPlayer player, Vec3 point, BlockPos exit, Vec3 target, List<Vec3> humans) {
        if (exit == null || ProtectionRuntime.protectedLevel(level) || !HuntBurrows.allowed(level)) return false;
        var pocket = BlockPos.containing(point);
        int dx = pocket.getX() - exit.getX(), dz = pocket.getZ() - exit.getZ();
        if (pocket.getY() != exit.getY() || Math.abs(dx) + Math.abs(dz) != 2 || dx != 0 && dz != 0) return false;
        // Cheapest first: most cells of a wide cave fail on distance alone.
        double distance = point.distanceTo(target);
        if (distance < SafeDeployment.BURROW_NEAR - EPSILON || distance > SafeDeployment.BURROW_FAR + EPSILON) return false;
        for (var human : humans)
            if (point.distanceToSqr(human) < SafeDeployment.BURROW_NEAR * SafeDeployment.BURROW_NEAR - EPSILON) return false;
        int ceiling = level.getMinY() + level.dimensionType().logicalHeight();
        if (target.y < ceiling && point.y >= ceiling) return false;
        var wall = exit.offset(dx / 2, 0, dz / 2);
        if (!SafeDeployment.loaded(level, new AABB(pocket).minmax(new AABB(exit)).inflate(1, 2, 1))
                || !level.getWorldBorder().isWithinBounds(pocket) || !level.getWorldBorder().isWithinBounds(exit)) return false;
        // The way out: an open standing cell, two blocks of plain ground, and the pocket behind them.
        for (var open : new BlockPos[]{exit, exit.above()}) {
            var state = level.getBlockState(open);
            if (!state.getCollisionShape(level, open).isEmpty() || !state.getFluidState().isEmpty()) return false;
        }
        if (level.getBlockState(exit.below()).getCollisionShape(level, exit.below()).isEmpty()) return false;
        var cells = List.of(pocket, pocket.above());
        var walls = List.of(wall, wall.above());
        for (var pos : List.of(pocket, pocket.above(), wall, wall.above())) {
            if (!MonsterDiggable.natural(level.getBlockState(pos)) || level.getServer().isUnderSpawnProtection(level, pos, player)) return false;
        }
        // Sealed: every face of the pocket meets opaque ground, so nobody sees in and nothing flows in.
        for (var cell : cells) for (var side : Direction.values()) {
            var neighbour = cell.relative(side);
            if (cells.contains(neighbour)) continue;
            var state = level.getBlockState(neighbour);
            if (!state.isSolidRender() || !state.getFluidState().isEmpty()) return false;
        }
        var floor = level.getBlockState(pocket.below());
        if (EntityTypes.PLAYER.isBlockDangerous(floor)) return false;
        // Nothing to drop into the hollowed pocket, nor to refill the opened wall.
        if (level.getBlockState(pocket.above(2)).getBlock() instanceof FallingBlock
                || level.getBlockState(wall.above(2)).getBlock() instanceof FallingBlock) return false;
        // Digging the wall out must not let water or lava in.
        for (var cell : walls) for (var side : Direction.values()) {
            if (!level.getFluidState(cell.relative(side)).isEmpty()) return false;
        }
        return true;
    }
}
