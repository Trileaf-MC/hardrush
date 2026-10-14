package dev.hardrush.persistence;

import java.util.Objects;
import java.util.UUID;

public record SnapshotData(UUID roundId, long version, byte[] payload) {
    public SnapshotData {
        Objects.requireNonNull(roundId, "roundId");
        if (version < 0) throw new IllegalArgumentException("Negative snapshot version");
        payload = Objects.requireNonNull(payload, "payload").clone();
    }

    @Override
    public byte[] payload() { return payload.clone(); }
}
