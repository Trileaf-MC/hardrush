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
 * A landing under water near a quarry out at sea, where a zombie rises as a drowned. Only a zombie, and only against a
 * drifting quarry: everyone else waits until it lands, and then the land rules put the monster on the shore.
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
        if (sweep.kind == MonsterRole.ZOMBIE && sweep.quarry.drifting()) {
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
        return SafeDeployment.allowed(level, candidate.point, target, humans, SafeDeployment.SEA_RADIUS + 4,
                SafeDeployment.MIN_HUMAN_DISTANCE)
                && SafeDeployment.canSubmergeAt(level, player, candidate.point);
    }

    /** The shallowest spot in this column where a whole body, and two more blocks above it, sit in water. */
    private void water(Sweep sweep, List<Candidate> list, Set<BlockPos> seen, double angle, double radius) {
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
            if (covered) { sweep.offer(list, seen, Vec3.atBottomCenterOf(new BlockPos(x, y, z)), false, SafeDeployment.SEA_RADIUS); return; }
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
        if (best == null || candidate.score > best.score) best = candidate;
    }
}
