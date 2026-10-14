package dev.hardrush.core.record;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** One observed, retryable aggregate. Completed IDs are unioned, never removed by respawn or reload. */
public record ProgressWrite(UUID roundId, UUID eventId, UUID playerId, String playerName,
                            Set<String> completedAdvancementIds, String advancementSetFingerprint,
                            boolean enteredEnd, long challengeNanos) {
    public ProgressWrite {
        Objects.requireNonNull(roundId); Objects.requireNonNull(eventId); Objects.requireNonNull(playerId);
        Objects.requireNonNull(playerName); Objects.requireNonNull(completedAdvancementIds);
        if (playerName.isBlank() || playerName.length() > 64 || playerName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid player name");
        }
        if (challengeNanos < 0 || completedAdvancementIds.size() > 10_000) throw new IllegalArgumentException("Invalid progress counters");
        if (advancementSetFingerprint == null || !advancementSetFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid advancement set fingerprint");
        }
        var ids = new TreeSet<String>();
        for (String id : completedAdvancementIds) {
            if (id == null || id.length() > 512 || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || id.substring(id.indexOf(':') + 1).startsWith("recipes/")
                    || id.substring(id.indexOf(':') + 1).equals("root") || id.endsWith("/root")) {
                throw new IllegalArgumentException("Invalid or excluded advancement ID");
            }
            ids.add(id);
        }
        completedAdvancementIds = java.util.Collections.unmodifiableSet(ids);
    }
}
