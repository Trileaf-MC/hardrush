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
    /** A breach's way in: the cell of the quarry's space beyond its wall or under its floor. */
    BlockPos exit;
    /** A sea landing on a cruising quarry's course, which may lie farther off than one beside a drifting quarry. */
    boolean ahead;
    /** A breach through the exit's ceiling rather than a wall beside it. */
    boolean above;
    /** A breach into a passage one block high, where the quarry lies prone. */
    boolean crawlway;
    double score;
    /** How far a partial path stopped short of the quarry; only meaningful for the closest walk. */
    double gap;

    Candidate(Vec3 point, boolean trail) { this.point = point; this.trail = trail; }
}
