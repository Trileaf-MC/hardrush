package dev.hardrush.hunt;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * One way of landing a monster near its quarry. {@link SafeDeployment.Search} drives the strategies of a sweep in order;
 * each one only reads the world, and its outputs are read by the search right when its stage of the sweep completes.
 */
interface LandingStrategy {
    /** Whether this sweep should try this strategy at all; asked when the stage before it completes. */
    default boolean applies(Sweep sweep) { return true; }
    /** The sweep reaches this strategy: forget the previous sweep and generate the candidates. */
    void begin(Sweep sweep);
    /** Keep judging candidates within this tick's budget; {@code true} once every candidate of this sweep is judged. */
    boolean step(Sweep sweep, long tickStartedNanos);
    /** This sweep's best landing, or {@code null}. */
    Candidate best();
    /** Judge the landing again, against the terrain and the humans as they are now, right before departure. */
    boolean revalidate(Candidate candidate, Vec3 target, List<Vec3> humans);
}
