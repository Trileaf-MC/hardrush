package dev.hardrush.core.round;

import java.util.Objects;
import java.util.UUID;

/** A proposed mutation becomes public only after the snapshot transaction succeeds. */
public record RoundTransition(UUID eventId, String eventType, RoundSnapshot before,
                              RoundSnapshot after, long capturedNanos) {
    public RoundTransition {
        Objects.requireNonNull(eventId); Objects.requireNonNull(eventType);
        Objects.requireNonNull(before); Objects.requireNonNull(after);
        if (!before.roundId().equals(after.roundId()) || after.version() != before.version() + 1) {
            throw new IllegalArgumentException("Invalid transition revision");
        }
    }
}
