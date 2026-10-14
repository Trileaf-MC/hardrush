package dev.hardrush.core.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Absence of a replay integration must return UNAVAILABLE, never a fictitious saved recording. */
public interface ReplayPort {
    record SavedRecordings(UUID roundId, List<String> recordingKeys) {
        public SavedRecordings {
            Objects.requireNonNull(roundId, "roundId");
            recordingKeys = List.copyOf(recordingKeys);
            if (recordingKeys.isEmpty() || recordingKeys.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("Verified recording keys required");
            }
        }
    }

    CompletionStage<OperationResult<UUID>> startSegment(OperationTag tag, UUID playerId);

    /** The world remains retained until this completion verifies all final writes. */
    CompletionStage<OperationResult<SavedRecordings>> stopAndFlush(OperationTag tag);
}
