package dev.hardrush.persistence;

import java.util.Objects;
import java.util.UUID;

/** Immutable audit evidence, separate from round state versions. */
public record EvidenceWrite(UUID roundId, UUID eventId, String kind, byte[] payload) {
    public EvidenceWrite {
        Objects.requireNonNull(roundId); Objects.requireNonNull(eventId);
        kind = StoreValues.text(kind, "evidence kind", 64);
        payload = Objects.requireNonNull(payload).clone();
        if (payload.length > 1_048_576) throw new IllegalArgumentException("Evidence exceeds 1 MiB");
    }
    @Override public byte[] payload() { return payload.clone(); }
}
