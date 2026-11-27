package dev.hardrush.hunt;

import dev.hardrush.core.round.MonsterRole;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A landing under water near a quarry out at sea, where a zombie rises as a drowned: anywhere around a drifting quarry,
 * or on the course of a cruising one, far enough ahead that the boat is still coming when the drowned rises. Only a
 * zombie: everyone else waits until it lands, and then the land rules put the monster on the shore.
 */
final class SeaLanding implements LandingStrategy {
    private final ServerLevel level;
    private final ServerPlayer player;
    private final ArrayDeque<Candidate> queue = new ArrayDeque<>();
    private Candidate best;

    SeaLanding(ServerLevel level, ServerPlayer player) { this.level = level; this.player = player; }

    @Override
    public void begin(Sweep sweep) {
        best = null;
        var seen = new HashSet<BlockPos>();
        var list = new ArrayList<Candidate>();
        if (sweep.kind == MonsterRole.ZOMBIE && !sweep.quarry.drifting()) ahead(sweep, list, seen);
        else if (sweep.kind == MonsterRole.ZOMBIE) {
            double phase = sweep.random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < SafeDeployment.SEA_COLUMNS; i++)
                water(sweep, list, seen, phase + i * Math.PI * (3 - Math.sqrt(5)),
                        SafeDeployment.MIN_HUMAN_DISTANCE + .5
                                + sweep.random.nextDouble() * (SafeDeployment.SEA_RADIUS - SafeDeployment.MIN_HUMAN_DISTANCE - 1));
        }
        queue.clear(); queue.addAll(list);
    }

    @Override
    public boolean step(Sweep sweep, long tickStartedNanos) {
        return sweep.drain(queue, candidate -> judge(sweep, candidate), tickStartedNanos);
    }

    @Override
    public Candidate best() { return best; }

    @Override
    public boolean revalidate(Candidate candidate, Vec3 target, List<Vec3> humans) {
        int radius = candidate.ahead ? SafeDeployment.SEA_AHEAD_RADIUS : SafeDeployment.SEA_RADIUS;
        return SafeDeployment.allowed(level, candidate.point, target, humans, radius + 4,
                SafeDeployment.MIN_HUMAN_DISTANCE)
                && SafeDeployment.canSubmergeAt(level, player, candidate.point);
    }

    /**
     * Eight columns across the quarry's course, 45 degrees either side, between two and three and a half seconds of its
     * cruise ahead (within {@link SafeDeployment#SEA_AHEAD_NEAR} to {@link SafeDeployment#SEA_AHEAD_RADIUS} blocks), the
     * distances staggered so neighbouring columns differ. No randomness is drawn.
     */
    private void ahead(Sweep sweep, List<Candidate> list, Set<BlockPos> seen) {
        var cruise = sweep.quarry.cruise();
        double speed = cruise.horizontalDistance();
        double near = Math.clamp(speed * SafeDeployment.SEA_AHEAD_FROM, SafeDeployment.SEA_AHEAD_NEAR, SafeDeployment.SEA_AHEAD_RADIUS);
        double far = Math.clamp(speed * SafeDeployment.SEA_AHEAD_TO, SafeDeployment.SEA_AHEAD_NEAR, SafeDeployment.SEA_AHEAD_RADIUS);
        double[] stagger = {0, 2 / 3.0, 1 / 3.0, 1};
        double base = Math.atan2(cruise.z, cruise.x);
        for (int i = 0; i < SafeDeployment.SEA_AHEAD_COLUMNS; i++) {
            double angle = base + Math.toRadians((i / (double) (SafeDeployment.SEA_AHEAD_COLUMNS - 1) - .5) * 90);
            int before = list.size();
            water(sweep, list, seen, angle, near + stagger[i % stagger.length] * (far - near), SafeDeployment.SEA_AHEAD_RADIUS);
            if (list.size() > before) list.getLast().ahead = true;
        }
    }

    /** The shallowest spot in this column where a whole body, and two more blocks above it, sit in water. */
    private void water(Sweep sweep, List<Candidate> list, Set<BlockPos> seen, double angle, double radius) {
        water(sweep, list, seen, angle, radius, SafeDeployment.SEA_RADIUS);
    }

    private void water(Sweep sweep, List<Candidate> list, Set<BlockPos> seen, double angle, double radius, int limit) {
        var quarry = sweep.quarry;
        int x = Mth.floor(quarry.position().x + Math.cos(angle) * radius);
        int z = Mth.floor(quarry.position().z + Math.sin(angle) * radius);
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return;
        int qy = Mth.floor(quarry.position().y);
        var cursor = new BlockPos.MutableBlockPos();
        for (int y = Math.min(qy, level.getMaxY() - SafeDeployment.SEA_COVER - 3); y >= Math.max(qy - 24, level.getMinY() + 1); y--) {
            boolean covered = true;
            for (int dy = 0; dy < 2 + SafeDeployment.SEA_COVER && covered; dy++) {
                cursor.set(x, y + dy, z);
                covered = level.getFluidState(cursor).is(FluidTags.WATER) && (dy >= 2 || sweep.empty(cursor));
            }
            if (covered) { sweep.offer(list, seen, Vec3.atBottomCenterOf(new BlockPos(x, y, z)), false, limit); return; }
        }
    }

    private void judge(Sweep sweep, Candidate candidate) {
        // Open water between them: a drowned swims straight, so the line of sight stands in for a path.
        var eye = candidate.point.add(0, player.getEyeHeight(), 0);
        if (!SafeDeployment.canSubmergeAt(level, player, candidate.point)) return;
        sweep.judged();
        if (!sweep.clear(eye, sweep.quarry.eye())) return;
        candidate.landing = SafeDeployment.Landing.SEA;
        candidate.score = WalkLanding.ambush(sweep, candidate, eye.distanceTo(sweep.quarry.eye()));
        if (candidate.ahead) {
            // Straight down the course first.
            var quarry = sweep.quarry;
            var away = new Vec3(candidate.point.x - quarry.position().x, 0, candidate.point.z - quarry.position().z);
            if (away.lengthSqr() > SafeDeployment.EPSILON)
                candidate.score += .5 * away.normalize().dot(quarry.cruise().multiply(1, 0, 1).normalize());
        }
        if (best == null || candidate.score > best.score) best = candidate;
    }
}
