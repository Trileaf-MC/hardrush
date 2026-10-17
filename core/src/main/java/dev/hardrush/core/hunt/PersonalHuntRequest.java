package dev.hardrush.core.hunt;

import dev.hardrush.core.round.*;
import java.util.Objects;
import java.util.UUID;

/** Immutable admission identity. A target's next life can never inherit an earlier selection. */
public record PersonalHuntRequest(UUID round, UUID hunter, int hunterLife, int initialSortie,
                                  MonsterRole kind, UUID target, int targetLife) {
    public PersonalHuntRequest {
        Objects.requireNonNull(round); Objects.requireNonNull(hunter); Objects.requireNonNull(kind);
        Objects.requireNonNull(target);
        if (hunter.equals(target) || hunterLife < 1 || targetLife < 1 || initialSortie < 0)
            throw new IllegalArgumentException("Invalid personal hunt identity");
    }

    public boolean hunterMatches(RoundSnapshot snapshot, ParticipantRole role, int sortie) {
        var p = snapshot.participants().get(hunter);
        return round.equals(snapshot.roundId()) && p != null && p.online() && p.role() == role
                && p.lifeSequence() == hunterLife && p.monsterDeploymentSequence() == sortie
                && p.monsterRole() == kind;
    }

    public boolean targetMatches(RoundSnapshot snapshot) {
        var p = snapshot.participants().get(target);
        return round.equals(snapshot.roundId()) && p != null && p.online()
                && p.role() == ParticipantRole.HUMAN_ACTIVE && p.lifeSequence() == targetLife;
    }
}
