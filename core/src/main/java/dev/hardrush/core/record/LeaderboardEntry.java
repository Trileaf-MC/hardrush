package dev.hardrush.core.record;

import java.util.Objects;

public record LeaderboardEntry(int rank, RoundHistory round, String winnerName) {
    public LeaderboardEntry {
        if (rank < 1) throw new IllegalArgumentException("Invalid rank");
        Objects.requireNonNull(round); Objects.requireNonNull(winnerName);
    }
}
