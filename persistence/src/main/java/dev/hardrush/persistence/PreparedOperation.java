package dev.hardrush.persistence;

import java.util.Objects;
import java.util.UUID;

/** An external effect to recover; expectedVersion identifies its committed round state. */
public record PreparedOperation(UUID operationId, String operationType, long expectedVersion, byte[] payload) {
    public PreparedOperation {
        Objects.requireNonNull(operationId, "operationId");
        operationType = StoreValues.text(operationType, "operationType", 256);
        if (expectedVersion < 0) throw new IllegalArgumentException("Negative operation precondition");
        payload = Objects.requireNonNull(payload, "payload").clone();
    }

    @Override
    public byte[] payload() { return payload.clone(); }
}
