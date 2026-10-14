package dev.hardrush.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SnapshotWrite(UUID roundId, long expectedVersion, long newVersion, byte[] payload,
                            UUID eventId, String eventType, List<PreparedOperation> operations) {
    public SnapshotWrite {
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(eventId, "eventId");
        eventType = StoreValues.text(eventType, "eventType", 256);
        if (expectedVersion < -1 || expectedVersion == Long.MAX_VALUE || newVersion != expectedVersion + 1) {
            throw new IllegalArgumentException("Snapshot version must advance by exactly one");
        }
        payload = Objects.requireNonNull(payload, "payload").clone();
        operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
        var ids = new HashSet<UUID>();
        for (var operation : operations) {
            if (operation.expectedVersion() != newVersion) {
                throw new IllegalArgumentException("Operation must refer to the committed snapshot version");
            }
            if (!ids.add(operation.operationId())) throw new IllegalArgumentException("Duplicate operation ID");
        }
    }

    public SnapshotWrite(UUID roundId, long expectedVersion, long newVersion, byte[] payload,
                         UUID eventId, String eventType) {
        this(roundId, expectedVersion, newVersion, payload, eventId, eventType, List.of());
    }

    @Override
    public byte[] payload() { return payload.clone(); }
}
