package dev.hardrush.core.combat;

import dev.hardrush.core.round.ParticipantRole;
import java.util.Objects;
import java.util.UUID;

/** Authoritative identity at creation, never reconstructed from game mode or a later session. */
public record CombatActor(UUID playerId, ParticipantRole identity, long lifeSequence,
                          long deploymentSequence) {
    public CombatActor {
        Objects.requireNonNull(playerId); Objects.requireNonNull(identity);
        if (lifeSequence < 0 || deploymentSequence < 0) throw new IllegalArgumentException("Negative identity sequence");
        if (identity == ParticipantRole.HUMAN_ACTIVE && lifeSequence == 0) {
            throw new IllegalArgumentException("Active human requires a life");
        }
        if (identity == ParticipantRole.MONSTER_DEPLOYED && deploymentSequence == 0) {
            throw new IllegalArgumentException("Deployed monster requires a deployment");
        }
    }
    public boolean mayCreateAttack() {
        return identity == ParticipantRole.HUMAN_ACTIVE || identity == ParticipantRole.MONSTER_DEPLOYED;
    }
}
