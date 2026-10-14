package dev.hardrush.core.record;

import dev.hardrush.core.round.ParticipantRole;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** First-admission identity and accumulated participation; terminal values remain frozen. */
public record ParticipantRecord(UUID playerId, String playerName, long joinedAtChallengeNanos,
                                long humanNanos, int deathCount, int revivalCount, int advancementCount,
                                Long firstEndNanos, ParticipantRole finalRole,
                                Set<String> advancementSetFingerprints) {
    public ParticipantRecord {
        Objects.requireNonNull(playerId); Objects.requireNonNull(playerName); Objects.requireNonNull(finalRole);
        if (playerName.isBlank() || playerName.length() > 64 || joinedAtChallengeNanos < 0 || humanNanos < 0
                || deathCount < 0 || revivalCount < 0 || advancementCount < 0 || firstEndNanos != null && firstEndNanos < 0) {
            throw new IllegalArgumentException("Invalid participant record");
        }
        advancementSetFingerprints = Set.copyOf(advancementSetFingerprints);
        if (advancementSetFingerprints.size() > 64 || advancementSetFingerprints.stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Invalid or unbounded advancement fingerprints");
        }
    }
    public long humanMillis() { return humanNanos / 1_000_000; }
    public String advancementSetFingerprint() { return advancementSetFingerprints.stream().sorted().reduce((a, b) -> a + "," + b).orElse(""); }
}
