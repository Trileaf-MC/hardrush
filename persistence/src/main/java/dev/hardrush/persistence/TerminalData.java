package dev.hardrush.persistence;

import dev.hardrush.core.combat.CombatEventCodec;
import java.util.Objects;
import java.util.UUID;

/** Immutable original SETTLING snapshot and actual causal evidence, even after later checkpoints. */
public record TerminalData(SnapshotWrite snapshot, EvidenceWrite evidence, UUID firstDragonId) {
    public TerminalData {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(evidence); Objects.requireNonNull(firstDragonId);
    }
    public UUID roundId() { return snapshot.roundId(); }
    public UUID eventId() { return snapshot.eventId(); }
    public long capturedNanos() { return CombatEventCodec.decode(evidence.payload()).observedNanoTime(); }
}
