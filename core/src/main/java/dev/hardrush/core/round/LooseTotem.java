package dev.hardrush.core.round;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A revival totem lying in the world (docs/plan/复生图腾守恒.md 1.1 to 1.4): released by a human life that ended, or by
 * a revival totem that saved one, and not paid to a monster. The snapshot's registry, keyed by the platform's drop
 * token, is the only proof the entity may be picked up. A registered totem never expires; the round's end clears it.
 *
 * @param victim whose death, or used revival totem, released it: its owner
 * @param victimLife the victim's human life that ended, or that the totem saved
 * @param source a human's qualifying attack, or anything else ({@link Source#UNCLAIMED})
 * @param killer the credited human attacker, present exactly for {@link Source#HUMAN}; it earns nothing by itself
 * @param droppedAtChallengeNanos challenge time of the death batch or totem save that registered it
 */
public record LooseTotem(UUID victim, int victimLife, Source source, Optional<UUID> killer, long droppedAtChallengeNanos) {
    /** Registry bound; registering beyond it evicts the oldest entry instead of failing. */
    public static final int MAX_REGISTERED = 1024;

    /** What released the totem. */
    public enum Source {
        /** A human's qualifying attack ({@link TotemDrop#qualifies}): told without coordinates, the killer is there. */
        HUMAN,
        /** No qualifying attacker at all: an accident, a suicide, the environment; its coordinates are told to all. */
        UNCLAIMED
    }

    public LooseTotem {
        Objects.requireNonNull(victim); Objects.requireNonNull(source); Objects.requireNonNull(killer);
        if (victimLife < 0 || droppedAtChallengeNanos < 0) throw new IllegalArgumentException("Invalid loose totem");
        if (killer.isPresent() != (source == Source.HUMAN)) throw new IllegalArgumentException("Only a human's drop has a killer");
    }

    /**
     * Whether {@code taker} is the very human life that released this totem (design 1.4): that life may never take it
     * back. Once it ends (the human became a monster) or a revival started a new one, the same player may.
     */
    public boolean ownedBy(Participant taker) {
        return taker.id().equals(victim) && taker.role() == ParticipantRole.HUMAN_ACTIVE && taker.lifeSequence() == victimLife;
    }
}
