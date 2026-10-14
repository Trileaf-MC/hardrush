package dev.hardrush.core.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Adapter captures actual causality at attack creation; querying must never guess the latest attacker. */
public interface DamagePort {
    enum SourceIdentity { HUMAN, MONSTER, ENVIRONMENT }
    enum AttackKind { MELEE, ARROW, BED, RESPAWN_ANCHOR, TNT, END_CRYSTAL, CREEPER, SUMMON, ENVIRONMENT }

    record AttackOrigin(UUID roundId, UUID attackId, Optional<UUID> ownerId, long lifeSequence,
            SourceIdentity identity, AttackKind kind, List<UUID> causalChain) {
        public AttackOrigin {
            Objects.requireNonNull(roundId, "roundId");
            Objects.requireNonNull(attackId, "attackId");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(kind, "kind");
            causalChain = List.copyOf(causalChain);
            if (lifeSequence < 0) throw new IllegalArgumentException("Negative source life sequence");
            if ((identity == SourceIdentity.ENVIRONMENT) == ownerId.isPresent()) {
                throw new IllegalArgumentException("Only player-origin attacks have an owner");
            }
        }
    }

    record LethalEvent(UUID roundId, UUID eventId, UUID damageBatchId, UUID victimId,
            Optional<AttackOrigin> origin, long observedNanoTime) {
        public LethalEvent {
            Objects.requireNonNull(roundId, "roundId");
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(damageBatchId, "damageBatchId");
            Objects.requireNonNull(victimId, "victimId");
            Objects.requireNonNull(origin, "origin");
            if (origin.isPresent() && !origin.get().roundId().equals(roundId)) throw new IllegalArgumentException("Cross-round damage origin");
        }
    }

    Optional<AttackOrigin> originOf(UUID roundId, UUID attackId);

    /** Called on the authoritative damage thread at the irreversible death point, before animation ends. */
    void onLethalEvent(java.util.function.Consumer<LethalEvent> listener);
}
