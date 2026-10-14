package dev.hardrush.core.record;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundResult;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RoundHistory(UUID roundId, long sequence, long seed, RulesConfig rules, RoundResult result,
                           long challengeNanos, boolean timingUncertain, boolean trustedWinner,
                           long edition, Set<String> advancementSetFingerprints) {
    public RoundHistory {
        Objects.requireNonNull(roundId); Objects.requireNonNull(rules); Objects.requireNonNull(result);
        if (sequence < 1 || challengeNanos < 0 || edition < 1) throw new IllegalArgumentException("Invalid history counters");
        advancementSetFingerprints = Set.copyOf(advancementSetFingerprints);
        if (advancementSetFingerprints.size() > 64 || advancementSetFingerprints.stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Invalid or unbounded advancement fingerprints");
        }
    }
    public long challengeMillis() { return challengeNanos / 1_000_000; }
    public String rulesFingerprint() { return rules.fingerprint(); }
    public boolean leaderboardEligible() {
        return result.kind() == RoundResult.Kind.COMPLETED && result.winner() != null && trustedWinner && !timingUncertain;
    }
}
