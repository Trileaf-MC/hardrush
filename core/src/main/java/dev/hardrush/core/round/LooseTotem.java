package dev.hardrush.core.round;

import java.util.Objects;
import java.util.UUID;

/**
 * A revival totem lying in the world after a human killed a human, or forced a revival totem out of another
 * human's hand (docs/plan/复生图腾掉落.md, 1.1, 1.2 and 1.5).
 * The snapshot's registry, keyed by the platform's drop token, is the only proof the entity may be picked up.
 *
 * @param victim whose death, or forced-out revival totem, dropped it
 * @param victimLife the victim's human life that ended, or that the totem saved
 * @param killer the credited attacker; informational, it earns nothing by itself
 * @param droppedAtChallengeNanos challenge time of the death batch or totem save that registered it
 */
public record LooseTotem(UUID victim, int victimLife, UUID killer, long droppedAtChallengeNanos) {
    /** Challenge (race) time a registered totem lies in the world before the registry forgets it. */
    public static final long LIFETIME_NANOS = 5 * 60 * 1_000_000_000L;
    /** Registry bound; registering beyond it evicts the oldest entry instead of failing. */
    public static final int MAX_REGISTERED = 1024;

    public LooseTotem {
        Objects.requireNonNull(victim); Objects.requireNonNull(killer);
        if (victimLife < 0 || droppedAtChallengeNanos < 0) throw new IllegalArgumentException("Invalid loose totem");
    }

    /** True once {@link #LIFETIME_NANOS} of challenge time has passed since the drop. */
    public boolean expiredAt(long challengeNanos) {
        return challengeNanos - droppedAtChallengeNanos >= LIFETIME_NANOS;
    }
}
