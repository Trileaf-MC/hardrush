package dev.hardrush.persistence;

import java.util.UUID;

/** Callers must check these tags on their owner thread before publishing a result. */
public record WriteCompletion(UUID roundId, UUID eventId, long version, CommitResult result) {}
