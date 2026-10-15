package dev.hardrush.core.round;

import dev.hardrush.core.combat.CombatEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One native, irreversible player death collected before its outer damage batch closes. */
public record RoundDeath(UUID playerId, int lifeSequence, int deploymentSequence,
                         Optional<CombatEvent> cause) {
    public RoundDeath {
        Objects.requireNonNull(playerId); Objects.requireNonNull(cause);
        if (lifeSequence < 1 || deploymentSequence < 0) throw new IllegalArgumentException("Invalid death identity");
        cause.ifPresent(event -> {
            if (!event.lethal() || !event.victimId().equals(playerId)) {
                throw new IllegalArgumentException("Death cause must be this player's actual lethal event");
            }
        });
    }
}
