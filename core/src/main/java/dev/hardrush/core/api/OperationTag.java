package dev.hardrush.core.api;

import java.util.Objects;
import java.util.UUID;

/** Every asynchronous completion must be checked against the current round and state version. */
public record OperationTag(UUID roundId, UUID operationId, long stateVersion) {
    public OperationTag {
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(operationId, "operationId");
        if (stateVersion < 0) throw new IllegalArgumentException("stateVersion must be nonnegative");
    }

    public boolean matches(UUID currentRoundId, long currentStateVersion) {
        return roundId.equals(currentRoundId) && stateVersion == currentStateVersion;
    }
}
