package dev.hardrush.core.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Authoritative player/session operations. Persisted identity remains owned by the rule core. */
public interface PlayerPort {
    record Presence(UUID playerId, String gameName, boolean online, boolean alive, Optional<WorldPosition> position) {
        public Presence {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(gameName, "gameName");
            Objects.requireNonNull(position, "position");
            if (gameName.isBlank()) throw new IllegalArgumentException("Blank player name");
        }
    }

    Optional<Presence> presence(UUID playerId);

    /** Isolation and reset must finish before a player can operate in the destination world. */
    CompletionStage<OperationResult<Presence>> prepareHuman(OperationTag tag, UUID playerId,
            long lifeSequence, WorldPosition safeDestination);

    CompletionStage<OperationResult<Presence>> moveToLobby(OperationTag tag, UUID playerId);
}
