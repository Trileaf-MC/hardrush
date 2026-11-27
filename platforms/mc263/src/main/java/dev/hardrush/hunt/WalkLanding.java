package dev.hardrush.hunt;

import static dev.hardrush.hunt.SafeDeployment.EPSILON;

import dev.hardrush.core.round.MonsterRole;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * A landing on land from which vanilla walking reaches the quarry within {@link SafeDeployment#MAX_PATH} blocks (a
 * skeleton may instead see it within bow range), at least {@link SafeDeployment#MIN_HUMAN_DISTANCE} from every human.
 * Candidates come from the quarry's footprints, the road ahead of a runner and a ring around it. Judging them also
 * keeps the closest reachable spot, the far {@link SafeDeployment.Landing#FALLBACK} of the search.
 */
final class WalkLanding implements LandingStrategy {
    private final ServerLevel level;
    private final ServerPlayer player;
    private final ArrayDeque<Candidate> queue = new ArrayDeque<>();
    private Candidate best, closest;
    /** How many candidates of this sweep the terrain admitted, and whether every one lay well under the quarry. */
    private int admitted;
    private boolean allBelow;

    WalkLanding(ServerLevel level, ServerPlayer player) { this.level = level; this.player = player; }

    @Override
    public void begin(Sweep sweep) {
        best = null; closest = null; admitted = 0; allBelow = true;
        var quarry = sweep.quarry;
        var seen = new HashSet<BlockPos>();
        var list = new ArrayList<Candidate>();
        // Footprints: the quarry walked here, so the way back to it usually exists.
        Vec3 previous = null; int trail = 0;
        for (var step : quarry.trail()) {
            if (trail == SafeDeployment.TRAIL_CANDIDATES) break;
            if (previous != null && previous.distanceToSqr(step) < 4) continue;
            if (sweep.offer(list, seen, step, true, SafeDeployment.FALLBACK_RADIUS)) { previous = step; trail++; }
        }
        if (quarry.moving()) {
            var heading = quarry.velocity().normalize();
            double base = Math.atan2(heading.z, heading.x);
            int[] distances = {12, 18, 15, 21};
            for (int i = 0; i < SafeDeployment.AHEAD_COLUMNS; i++) {
                double angle = base + Math.toRadians((i / (double) (SafeDeployment.AHEAD_COLUMNS - 1) - .5) * 90);
                column(sweep, list, seen, angle, distances[i % distances.length]);
            }
        }
        double phase = sweep.random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < SafeDeployment.RING_COLUMNS + SafeDeployment.OUTER_COLUMNS; i++) {
            double angle = phase + i * Math.PI * (3 - Math.sqrt(5));
            double radius = i < SafeDeployment.RING_COLUMNS
                    ? SafeDeployment.MIN_HUMAN_DISTANCE + .5
                            + sweep.random.nextDouble() * (SafeDeployment.MAX_PATH - SafeDeployment.MIN_HUMAN_DISTANCE - 1)
                    : SafeDeployment.MAX_PATH + .5
                            + sweep.random.nextDouble() * (SafeDeployment.FALLBACK_RADIUS - SafeDeployment.MAX_PATH - 1);
            column(sweep, list, seen, angle, radius);
        }
        queue.clear(); queue.addAll(list.subList(0, Math.min(SafeDeployment.MAX_CANDIDATES, list.size())));
    }

    @Override
    public boolean step(Sweep sweep, long tickStartedNanos) {
        return sweep.drain(queue, candidate -> judge(sweep, candidate), tickStartedNanos);
    }

    @Override
    public Candidate best() { return best; }
    /** Every spot this sweep's terrain admitted, at least one, lay {@link SafeDeployment#ELEVATED_DROP} or more under the quarry. */
    boolean below() { return admitted > 0 && allBelow; }
    /** The spot this sweep could walk closest to the quarry from, when none reached it; or {@code null}. */
    Candidate closest() { return closest; }

    @Override
    public boolean revalidate(Candidate candidate, Vec3 target, List<Vec3> humans) {
        double radius = candidate.landing == SafeDeployment.Landing.WALK ? SafeDeployment.MAX_PATH + 4 : SafeDeployment.FALLBACK_RADIUS;
        return SafeDeployment.allowed(level, candidate.point, target, humans, radius, SafeDeployment.MIN_HUMAN_DISTANCE)
                && SafeDeployment.canDeployAt(level, player, candidate.point);
    }

    /** The two floors in this column nearest the quarry's height. */
    private void column(Sweep sweep, List<Candidate> list, Set<BlockPos> seen, double angle, double radius) {
        var quarry = sweep.quarry;
        int x = Mth.floor(quarry.position().x + Math.cos(angle) * radius);
        int z = Mth.floor(quarry.position().z + Math.sin(angle) * radius);
        for (var floor : floors(sweep, x, z, SafeDeployment.FLOORS_PER_COLUMN))
            sweep.offer(list, seen, Vec3.atBottomCenterOf(floor), false, SafeDeployment.FALLBACK_RADIUS);
    }

    /**
     * The at most {@code limit} floors of column {@code (x, z)} nearest the quarry's height, from eight blocks above it
     * to twenty-four below: open cells with room to stand over something solid. None in a chunk not loaded yet.
     */
    static List<BlockPos> floors(Sweep sweep, int x, int z, int limit) {
        var level = sweep.level;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return List.of();
        int qy = Mth.floor(sweep.quarry.position().y);
        int top = Math.min(qy + 8, level.getMaxY() - 2), bottom = Math.max(qy - 24, level.getMinY() + 1);
        var floors = new ArrayList<BlockPos>();
        var cursor = new BlockPos.MutableBlockPos();
        for (int y = top; y >= bottom; y--) {
            cursor.set(x, y, z);
            if (sweep.empty(cursor) && sweep.empty(cursor.above()) && !sweep.empty(cursor.below())) floors.add(cursor.immutable());
        }
        floors.sort(Comparator.comparingInt(pos -> Math.abs(pos.getY() - qy)));
        return floors.subList(0, Math.min(limit, floors.size()));
    }

    /**
     * How far the quarry is from a landing it reaches: the length of a vanilla walk within {@link SafeDeployment#MAX_PATH},
     * or for a skeleton the sight line within bow range, whichever is shorter; infinite when neither reaches it.
     */
    static double reach(Sweep sweep, Vec3 point, Path path) {
        var quarry = sweep.quarry;
        double length = path != null && path.canReach() ? SafeDeployment.length(path) : Double.POSITIVE_INFINITY;
        var eye = point.add(0, sweep.player.getEyeHeight(), 0);
        boolean sight = sweep.kind == MonsterRole.SKELETON && eye.distanceTo(quarry.eye()) <= SafeDeployment.SKELETON_SIGHT
                && sweep.clear(eye, quarry.eye());
        if (length <= SafeDeployment.MAX_PATH + EPSILON || sight) return Math.min(length, eye.distanceTo(quarry.eye()));
        return Double.POSITIVE_INFINITY;
    }

    private void judge(Sweep sweep, Candidate candidate) {
        if (!SafeDeployment.canDeployAt(level, player, candidate.point)) return;
        sweep.judged();
        admitted++;
        if (candidate.point.y > sweep.quarry.position().y - SafeDeployment.ELEVATED_DROP + EPSILON) allBelow = false;
        var path = sweep.path(candidate.point);
        double length = path != null && path.canReach() ? SafeDeployment.length(path) : Double.POSITIVE_INFINITY;
        double reach = reach(sweep, candidate.point, path);
        if (Double.isFinite(reach)) {
            candidate.landing = SafeDeployment.Landing.WALK;
            candidate.score = ambush(sweep, candidate, reach);
            if (best == null || candidate.score > best.score) best = candidate;
        } else if (path != null) {
            // Closest walkable spot: a reachable long walk counts as no gap at all.
            candidate.landing = SafeDeployment.Landing.FALLBACK;
            candidate.gap = Double.isFinite(length) ? 0 : path.getDistToTarget();
            candidate.score = ambush(sweep, candidate, SafeDeployment.IDEAL_PATH);
            if (closest == null || candidate.gap < closest.gap - EPSILON
                    || Math.abs(candidate.gap - closest.gap) <= EPSILON && candidate.score > closest.score) closest = candidate;
        }
    }

    /**
     * How good an ambush this landing is, {@code walk} blocks from the quarry: in the way it runs, or behind its back
     * along its footprints, out of its sight, inside its structure, in the dark. The sea scores its landings the same way.
     */
    static double ambush(Sweep sweep, Candidate candidate, double walk) {
        var quarry = sweep.quarry;
        var point = candidate.point;
        double score = 1 - Math.min(1, Math.abs(walk - SafeDeployment.IDEAL_PATH) / 10.0);
        var away = new Vec3(point.x - quarry.position().x, 0, point.z - quarry.position().z);
        var direction = away.lengthSqr() < EPSILON ? Vec3.ZERO : away.normalize();
        if (quarry.moving()) {
            if (direction.dot(quarry.velocity().normalize()) >= Math.cos(Math.PI / 4)) score += .8;
        } else {
            if (candidate.trail) score += .5;
            double yaw = Math.toRadians(quarry.yaw());
            var look = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
            if (direction.dot(look) <= -.5) score += .35;
        }
        if (!sweep.clear(quarry.eye(), point.add(0, sweep.player.getEyeHeight(), 0))) score += .35;
        var block = BlockPos.containing(point);
        if (sweep.lair != null && sweep.lair.getPieces().stream().anyMatch(piece -> piece.getBoundingBox().isInside(block))) score += .5;
        score += .3 * (1 - sweep.level.getMaxLocalRawBrightness(block) / 15.0);
        return score;
    }
}
