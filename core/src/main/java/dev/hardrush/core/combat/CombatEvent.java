package dev.hardrush.core.combat;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Actual damage evidence; pending attribution never implies the nearest or last player. */
public record CombatEvent(UUID roundId, UUID eventId, UUID batchId, long sequence, UUID victimId,
                          Optional<CombatOrigin> origin, Attribution attribution, String rawDamageType,
                          Optional<UUID> rawDirectEntityId, Optional<UUID> rawCausingEntityId,
                          float appliedDamage, boolean lethal, long observedNanoTime) {
    public enum Attribution { HUMAN, MONSTER, PENDING_VERIFICATION, STALE_ROUND }
    public CombatEvent {
        Objects.requireNonNull(roundId); Objects.requireNonNull(eventId); Objects.requireNonNull(batchId);
        Objects.requireNonNull(victimId); Objects.requireNonNull(origin); Objects.requireNonNull(attribution);
        Objects.requireNonNull(rawDamageType);
        Objects.requireNonNull(rawDirectEntityId); Objects.requireNonNull(rawCausingEntityId);
        if (sequence < 0 || !Float.isFinite(appliedDamage) || appliedDamage < 0) throw new IllegalArgumentException("Invalid damage evidence");
        if (attribution != classify(roundId, origin)) throw new IllegalArgumentException("Attribution does not match immutable source");
    }
    public static Attribution classify(UUID roundId, Optional<CombatOrigin> origin) {
        if (origin.isEmpty()) return Attribution.PENDING_VERIFICATION;
        if (!origin.get().roundId().equals(roundId)) return Attribution.STALE_ROUND;
        return origin.get().actor().identity().isActiveHuman() ? Attribution.HUMAN : Attribution.MONSTER;
    }
}
