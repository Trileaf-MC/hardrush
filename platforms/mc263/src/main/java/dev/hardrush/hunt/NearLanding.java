package dev.hardrush.hunt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A landing on the quarry's own small patch of ground (a platform, an islet, a room without walls to dig) when no walk
 * from {@link SafeDeployment#MIN_HUMAN_DISTANCE} away and no breach turned up. Judged exactly like a walk, only
 * {@link SafeDeployment#NEAR_HUMAN_DISTANCE} from every human, and as far from the quarry as the patch allows. Every
 * column around the quarry is looked at rather than a random ring: on a small patch only a few spots may qualify.
 */
final class NearLanding implements LandingStrategy {
    private final ServerLevel level;
    private final ServerPlayer player;
    private final ArrayDeque<Candidate> queue = new ArrayDeque<>();
    private Candidate best;

    NearLanding(ServerLevel level, ServerPlayer player) { this.level = level; this.player = player; }

    /** Only once the previous land sweep's walk found nothing either; never in the search's first sweep. */
    @Override
    public boolean applies(Sweep sweep) { return !sweep.walkedBefore; }

    /**
     * The floor nearest the quarry's height in every column within {@link SafeDeployment#NEAR_RADIUS} blocks, when it
     * is on the quarry's own level ({@link SafeDeployment#NEAR_RISE} up or down), at least
     * {@link SafeDeployment#NEAR_HUMAN_DISTANCE} from every human and fit for the hunter's body; of those, the farthest
     * {@link SafeDeployment#NEAR_CANDIDATES} go on to pathfinding. Filtering first keeps unreachable ground far above
     * or below (the top of a rock the quarry is sealed in) from crowding out the spots it can actually walk to.
     */
    @Override
    public void begin(Sweep sweep) {
        best = null;
        var quarry = sweep.quarry.position();
        int cx = Mth.floor(quarry.x), cz = Mth.floor(quarry.z), r = SafeDeployment.NEAR_RADIUS, rise = SafeDeployment.NEAR_RISE;
        var list = new ArrayList<Candidate>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r) continue;
            for (BlockPos floor : WalkLanding.floors(sweep, cx + dx, cz + dz, 1, rise, rise)) {
                var point = Vec3.atBottomCenterOf(floor);
                if (allowed(point, quarry, sweep.humans()) && SafeDeployment.canDeployAt(level, player, point))
                    list.add(new Candidate(point, false));
            }
        }
        list.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.point.distanceToSqr(quarry)).reversed());
        queue.clear(); queue.addAll(list.subList(0, Math.min(SafeDeployment.NEAR_CANDIDATES, list.size())));
    }

    @Override
    public boolean step(Sweep sweep, long tickStartedNanos) {
        return sweep.drain(queue, candidate -> judge(sweep, candidate), tickStartedNanos);
    }

    @Override
    public Candidate best() { return best; }

    @Override
    public boolean revalidate(Candidate candidate, Vec3 target, List<Vec3> humans) {
        return allowed(candidate.point, target, humans) && SafeDeployment.canDeployAt(level, player, candidate.point);
    }

    /** As far from the quarry as a walk may be judged again, and never closer than five blocks to any human. */
    private boolean allowed(Vec3 point, Vec3 target, List<Vec3> humans) {
        return SafeDeployment.allowed(level, point, target, humans, SafeDeployment.MAX_PATH + 4, SafeDeployment.NEAR_HUMAN_DISTANCE);
    }

    /** Terrain was already admitted in {@link #begin}; this is the walk (or a skeleton's sight) only. */
    private void judge(Sweep sweep, Candidate candidate) {
        sweep.judged();
        double reach = WalkLanding.reach(sweep, candidate.point, sweep.path(candidate.point));
        if (!Double.isFinite(reach)) return;
        candidate.landing = SafeDeployment.Landing.NEAR;
        // The walk's own ambush score, pushed into the patch's farthest corner.
        candidate.score = WalkLanding.ambush(sweep, candidate, reach) + .1 * candidate.point.distanceTo(sweep.quarry.position());
        if (best == null || candidate.score > best.score) best = candidate;
    }
}
