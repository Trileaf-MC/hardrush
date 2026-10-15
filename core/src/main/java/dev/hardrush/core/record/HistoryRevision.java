package dev.hardrush.core.record;

import dev.hardrush.core.round.RoundResult;
import java.util.Objects;
import java.util.UUID;

/** Append-only correction of a historical projection; never an update of terminal evidence. */
public record HistoryRevision(UUID operationId, UUID roundId, long expectedEdition, RoundResult result,
                              long challengeNanos, boolean timingUncertain, Action action) {
    public enum Action { CORRECT, VERIFY }
    public HistoryRevision {
        Objects.requireNonNull(operationId); Objects.requireNonNull(roundId);
        Objects.requireNonNull(result); Objects.requireNonNull(action);
        if (expectedEdition < 1 || expectedEdition >= 1001 || challengeNanos < 0) {
            throw new IllegalArgumentException("Invalid or unbounded history revision");
        }
    }
}
