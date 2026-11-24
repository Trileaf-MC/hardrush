package dev.hardrush.core.round;

import dev.hardrush.core.config.RulesConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable accumulated values. No process-local clock origin or wall-clock race arithmetic. */
public record RoundSnapshot(UUID roundId, long sequence, long seed, long version, RulesConfig rules,
                            RoundPhase phase, PauseReason pauseReason, long challengeNanos,
                            long remainingPhaseNanos, Map<UUID, Participant> participants,
                            boolean cleanShutdown, boolean timingUncertain, RoundResult result,
                            Map<UUID, LooseTotem> looseTotems) {
    /** A snapshot with no revival totem lying in the world. */
    public RoundSnapshot(UUID roundId, long sequence, long seed, long version, RulesConfig rules,
                         RoundPhase phase, PauseReason pauseReason, long challengeNanos,
                         long remainingPhaseNanos, Map<UUID, Participant> participants,
                         boolean cleanShutdown, boolean timingUncertain, RoundResult result) {
        this(roundId, sequence, seed, version, rules, phase, pauseReason, challengeNanos, remainingPhaseNanos,
                participants, cleanShutdown, timingUncertain, result, Map.of());
    }

    public RoundSnapshot {
        Objects.requireNonNull(roundId); Objects.requireNonNull(rules);
        Objects.requireNonNull(phase); Objects.requireNonNull(pauseReason); Objects.requireNonNull(participants);
        Objects.requireNonNull(looseTotems);
        if (sequence < 1 || version < 0 || challengeNanos < 0 || remainingPhaseNanos < 0) {
            throw new IllegalArgumentException("Invalid round counters");
        }
        participants.forEach((id, player) -> {
            if (!id.equals(player.id())) throw new IllegalArgumentException("Participant key mismatch");
        });
        participants = Collections.unmodifiableMap(new LinkedHashMap<>(participants));
        looseTotems.forEach((token, totem) -> { Objects.requireNonNull(token); Objects.requireNonNull(totem); });
        if (looseTotems.size() > LooseTotem.MAX_REGISTERED) throw new IllegalArgumentException("Too many loose totems");
        looseTotems = Collections.unmodifiableMap(new LinkedHashMap<>(looseTotems));
        boolean terminal = phase == RoundPhase.SETTLING || phase == RoundPhase.CELEBRATING || phase == RoundPhase.ROTATING;
        if (terminal && result == null || !terminal && phase != RoundPhase.RECOVERY_REQUIRED && result != null) {
            throw new IllegalArgumentException("Round phase and result disagree");
        }
        if (result != null && result.winner() != null && !participants.containsKey(result.winner())) {
            throw new IllegalArgumentException("Winner must belong to this round");
        }
        if (result != null && result.winner() != null) {
            Participant winner = participants.get(result.winner());
            if (winner.lifeSequence() == 0 || winner.joinedAtChallengeNanos() < 0) {
                throw new IllegalArgumentException("Winner must have entered a human life in this round");
            }
        }
    }

    /** The registered loose totem behind {@code token}; registrations never expire within a round. */
    public java.util.Optional<LooseTotem> looseTotem(UUID token) {
        return java.util.Optional.ofNullable(looseTotems.get(token));
    }

    public long challengeMillis() { return challengeNanos / 1_000_000; }
    public long remainingPhaseMillis() { return remainingPhaseNanos / 1_000_000; }
    public long onlineHumans() { return participants.values().stream().filter(p -> p.online() && p.role().isActiveHuman()).count(); }
    /** A rescue that started because every remaining human went offline; their reconnect also ends it. */
    public boolean awaitingReconnect() {
        return phase == RoundPhase.RESCUE && participants.values().stream().anyMatch(p -> p.role().isActiveHuman());
    }
}
