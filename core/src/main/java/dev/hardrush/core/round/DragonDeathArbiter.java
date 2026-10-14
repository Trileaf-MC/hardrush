package dev.hardrush.core.round;

import dev.hardrush.core.combat.CombatEvent;
import java.util.Objects;
import java.util.UUID;

/** Pure first-dragon rule: attribution comes from the immutable attack creation identity. */
public final class DragonDeathArbiter {
    private DragonDeathArbiter() {}

    public static RoundResult adjudicate(RoundSnapshot captured, CombatEvent death, UUID firstDragonId) {
        Objects.requireNonNull(captured); Objects.requireNonNull(death); Objects.requireNonNull(firstDragonId);
        if (captured.result() != null) throw new IllegalStateException("Round already has a terminal result");
        if (captured.phase() != RoundPhase.RUNNING && captured.phase() != RoundPhase.RESCUE) {
            throw new IllegalStateException("Dragon death is only accepted during a running round");
        }
        if (!death.roundId().equals(captured.roundId()) || !death.victimId().equals(firstDragonId) || !death.lethal()) {
            throw new IllegalArgumentException("Evidence must be this round's actual first-dragon death");
        }
        return switch (death.attribution()) {
            case PENDING_VERIFICATION -> completed(null, "DRAGON_DEATH_PENDING_VERIFICATION:UNKNOWN_SOURCE");
            case STALE_ROUND -> completed(null, "DRAGON_DEATH_PENDING_VERIFICATION:STALE_ROUND_SOURCE");
            case MONSTER -> completed(null, "DRAGON_DEATH_PENDING_VERIFICATION:MONSTER_SOURCE");
            case HUMAN -> {
                var actor = death.origin().orElseThrow().actor();
                var participant = captured.participants().get(actor.playerId());
                if (participant == null || participant.joinedAtChallengeNanos() < 0
                        || participant.lifeSequence() == 0 || actor.lifeSequence() > participant.lifeSequence()) {
                    yield completed(null, "DRAGON_DEATH_PENDING_VERIFICATION:UNADMITTED_SOURCE");
                }
                // An arrow/explosion stays human after the owner dies, disconnects or changes role.
                // A death in this same batch therefore cannot revoke an already valid human attack.
                yield completed(actor.playerId(), "FIRST_DRAGON_HUMAN_FINAL_HIT");
            }
        };
    }

    private static RoundResult completed(UUID winner, String reason) {
        return new RoundResult(RoundResult.Kind.COMPLETED, winner, reason);
    }
}
