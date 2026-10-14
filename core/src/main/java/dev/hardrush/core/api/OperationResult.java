package dev.hardrush.core.api;

import java.util.Objects;
import java.util.Optional;

/** An adapter result is evidence, not permission to mutate a newer round. */
public record OperationResult<T>(OperationTag tag, Outcome outcome, Optional<T> value, Optional<String> detail) {
    public enum Outcome { APPLIED, VERIFIED, REJECTED, RETRYABLE_FAILURE, UNAVAILABLE }

    public OperationResult {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(detail, "detail");
        detail.ifPresent(message -> { if (message.isBlank()) throw new IllegalArgumentException("Blank result detail"); });
        if ((outcome == Outcome.APPLIED || outcome == Outcome.VERIFIED) != value.isPresent()) {
            throw new IllegalArgumentException("Only applied/verified operations carry a result value");
        }
        if (value.isEmpty() && detail.isEmpty()) throw new IllegalArgumentException("Failed operations require a reason");
    }

    public static <T> OperationResult<T> verified(OperationTag tag, T value) {
        return new OperationResult<>(tag, Outcome.VERIFIED, Optional.of(value), Optional.empty());
    }

    public static <T> OperationResult<T> failed(OperationTag tag, Outcome outcome, String detail) {
        return new OperationResult<>(tag, outcome, Optional.empty(), Optional.of(detail));
    }
}
