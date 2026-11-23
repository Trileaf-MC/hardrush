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
 *
 * <p>{@code dropToken} names the revival totem the adapter dropped at this death because
 * {@link TotemDrop#qualifies} held when it died; the engine registers it only if that still holds on commit.
 *
 * <p>{@code detonation} is present when a deployed creeper's body was spent in its own blast; it decides whom the
 * defeat locks instead of the kill credit alone (docs/plan/击败锁定与怪物平衡.md §1).
 */
public record RoundDeath(UUID playerId, int lifeSequence, int deploymentSequence,
                         Optional<CombatEvent> cause, Optional<CombatOrigin> killCredit,
                         boolean recentlyHurtByPlayer, Optional<UUID> dropToken, Optional<Detonation> detonation) {
    /**
     * A creeper player's blast that killed its own body.
     *
     * @param quarry  the human this sortie was deployed onto, when the adapter still knew it
     * @param igniter the human who lit the fuse with a native igniter, when one did; locked whoever the kill credit names
     */
    public record Detonation(Optional<UUID> quarry, Optional<UUID> igniter) {
        public Detonation { Objects.requireNonNull(quarry); Objects.requireNonNull(igniter); }
    }

    /** A death that was not a creeper's own blast. */
    public RoundDeath(UUID playerId, int lifeSequence, int deploymentSequence,
                      Optional<CombatEvent> cause, Optional<CombatOrigin> killCredit,
                      boolean recentlyHurtByPlayer, Optional<UUID> dropToken) {
        this(playerId, lifeSequence, deploymentSequence, cause, killCredit, recentlyHurtByPlayer, dropToken, Optional.empty());
    }

    /** A death that dropped no revival totem. */
    public RoundDeath(UUID playerId, int lifeSequence, int deploymentSequence,
                      Optional<CombatEvent> cause, Optional<CombatOrigin> killCredit, boolean recentlyHurtByPlayer) {
        this(playerId, lifeSequence, deploymentSequence, cause, killCredit, recentlyHurtByPlayer, Optional.empty());
    }

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
        Objects.requireNonNull(dropToken); Objects.requireNonNull(detonation);
        if (lifeSequence < 1 || deploymentSequence < 0) throw new IllegalArgumentException("Invalid death identity");
        cause.ifPresent(event -> {
            if (!event.lethal() || !event.victimId().equals(playerId)) {
                throw new IllegalArgumentException("Death cause must be this player's actual lethal event");
            }
        });
    }

    public RoundDeath withDropToken(UUID token) {
        return new RoundDeath(playerId, lifeSequence, deploymentSequence, cause, killCredit, recentlyHurtByPlayer,
                Optional.of(token), detonation);
    }

    public RoundDeath withCause(CombatEvent event) {
        return new RoundDeath(playerId, lifeSequence, deploymentSequence, Optional.of(event), killCredit, recentlyHurtByPlayer,
                dropToken, detonation);
    }
}
