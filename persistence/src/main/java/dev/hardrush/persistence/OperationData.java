package dev.hardrush.persistence;

import java.util.Objects;
import java.util.UUID;

public record OperationData(UUID roundId, UUID operationId, UUID eventId, String operationType,
                            long expectedVersion, byte[] payload, OperationStage stage, long version) {
    public OperationData {
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(stage, "stage");
        if (expectedVersion < 0 || version < 0) throw new IllegalArgumentException("Negative operation version");
        payload = Objects.requireNonNull(payload, "payload").clone();
    }

    @Override
    public byte[] payload() { return payload.clone(); }
}
