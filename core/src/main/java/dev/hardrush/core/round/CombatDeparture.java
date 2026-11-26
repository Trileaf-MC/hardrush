package dev.hardrush.core.round;

import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.combat.KillCreditMemory;
import dev.hardrush.core.config.GameMode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Leaving in the middle of a fight (docs/plan/战斗中断线.md): a hardcore human who leaves the game while another player
 * still holds the kill credit for them takes the lethal hit the departure stands for. The credit is vanilla's own
 * window ({@link KillCreditMemory#WINDOW_TICKS}), so a human nobody has hurt lately leaves as before, keeping the place.
 * Disconnects the server itself made (a stop, an administrator's kick) never reach this rule; the platform filters them.
 */
public final class CombatDeparture {
    private CombatDeparture() {}

    /** The vanilla kill-credit window in whole seconds, as the rule book words it. */
    public static long windowSeconds() { return KillCreditMemory.WINDOW_TICKS / 20; }

    /**
     * Whether {@code playerId} leaving {@code round} right now, while credited to {@code credit}, counts as that lethal
     * hit: a hardcore round in play, an active human, and a credit some other player of this round earned.
     */
    public static boolean countsAsLethal(RoundSnapshot round, UUID playerId, Optional<CombatOrigin> credit) {
        Objects.requireNonNull(round); Objects.requireNonNull(playerId); Objects.requireNonNull(credit);
        if (round.rules().mode() != GameMode.HARDCORE) return false;
        if (round.phase() != RoundPhase.RUNNING && round.phase() != RoundPhase.RESCUE) return false;
        var participant = round.participants().get(playerId);
        if (participant == null || participant.role() != ParticipantRole.HUMAN_ACTIVE) return false;
        // Hurting yourself (your own TNT, a bed in the Nether) is no fight.
        return credit.filter(origin -> origin.roundId().equals(round.roundId())
                && !origin.actor().playerId().equals(playerId)).isPresent();
    }
}
