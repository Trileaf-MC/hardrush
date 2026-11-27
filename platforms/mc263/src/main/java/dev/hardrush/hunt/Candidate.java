package dev.hardrush.hunt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** One landing a {@link LandingStrategy} proposed during a sweep, with what it learnt while judging it. */
final class Candidate {
    /** The feet of the landing (for a burrow, the floor of its pocket). */
    final Vec3 point;
    /** Taken from the quarry's own footprints, which an ambush prefers when the quarry stands still. */
    final boolean trail;
    /** What kind of landing this is once judged; {@code null} until a strategy accepts it. */
    SafeDeployment.Landing landing;
    /** A burrow's way out: the open cell beside its wall, from which the quarry is walked to. */
    BlockPos exit;
    double score;
    /** How far a partial path stopped short of the quarry; only meaningful for the closest walk. */
    double gap;

    Candidate(Vec3 point, boolean trail) { this.point = point; this.trail = trail; }
}
