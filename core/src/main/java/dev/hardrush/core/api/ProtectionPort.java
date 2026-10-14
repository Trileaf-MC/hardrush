package dev.hardrush.core.api;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Dynamic-dimension protection must be configured before the first chunk is exposed to a client. */
public interface ProtectionPort {
    record ProtectedWorlds(Set<String> worldKeys, String policyFingerprint) {
        public ProtectedWorlds {
            worldKeys = Set.copyOf(worldKeys);
            Objects.requireNonNull(policyFingerprint, "policyFingerprint");
            if (worldKeys.isEmpty() || worldKeys.stream().anyMatch(String::isBlank) || policyFingerprint.isBlank()) {
                throw new IllegalArgumentException("Invalid protected-world evidence");
            }
        }
    }

    CompletionStage<OperationResult<ProtectedWorlds>> configureBeforeExposure(OperationTag tag, WorldPort.WorldSet worlds);
}
