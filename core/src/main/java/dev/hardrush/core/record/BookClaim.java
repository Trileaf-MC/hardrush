package dev.hardrush.core.record;

import java.util.Objects;
import java.util.UUID;

public record BookClaim(UUID playerId, UUID roundId, int edition, int volume, boolean delivered) {
    public BookClaim {
        Objects.requireNonNull(playerId); Objects.requireNonNull(roundId);
        if (edition < 1 || volume < 1 || volume > 10_000) throw new IllegalArgumentException("Invalid book key");
    }
}
