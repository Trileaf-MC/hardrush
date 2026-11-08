package dev.hardrush.core.round;

import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatOrigin;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One native, irreversible player death collected before its outer damage batch closes.
 *
 * <p>{@code cause} is the actual lethal hit; {@code killCredit} is whom the kill is credited to, which
 * the adapter resolves like vanilla's {@code getKillCredit}: the lethal hit's own source, otherwise the
 * last attributed hit within {@link dev.hardrush.core.combat.KillCreditMemory#WINDOW_TICKS}. A fall or
 * burn after a player's hit is therefore still that player's kill.
 */
public record RoundDeath(UUID playerId, int lifeSequence, int deploymentSequence,
                         Optional<CombatEvent> cause, Optional<CombatOrigin> killCredit,
                         boolean recentlyHurtByPlayer) {
    /** The lethal hit's own source is the credit; no native player-damage memory for the monster death reward. */
    public RoundDeath(UUID playerId, int lifeSequence, int deploymentSequence, Optional<CombatEvent> cause) {
        this(playerId, lifeSequence, deploymentSequence, cause, false);
    }

    /** The lethal hit's own source is the credit. */
    public RoundDeath(UUID playerId, int lifeSequence, int deploymentSequence, Optional<CombatEvent> cause,
                      boolean recentlyHurtByPlayer) {
        this(playerId, lifeSequence, deploymentSequence, cause,
                Objects.requireNonNull(cause).flatMap(CombatEvent::origin), recentlyHurtByPlayer);
    }

    public RoundDeath {
        Objects.requireNonNull(playerId); Objects.requireNonNull(cause); Objects.requireNonNull(killCredit);
        if (lifeSequence < 1 || deploymentSequence < 0) throw new IllegalArgumentException("Invalid death identity");
        cause.ifPresent(event -> {
            if (!event.lethal() || !event.victimId().equals(playerId)) {
                throw new IllegalArgumentException("Death cause must be this player's actual lethal event");
            }
        });
    }

    public RoundDeath withCause(CombatEvent event) {
        return new RoundDeath(playerId, lifeSequence, deploymentSequence, Optional.of(event), killCredit, recentlyHurtByPlayer);
    }
}
