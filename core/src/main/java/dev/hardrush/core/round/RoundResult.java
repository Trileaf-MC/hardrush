package dev.hardrush.core.round;

import java.util.Objects;
import java.util.UUID;

/** Terminal event contract; causal dragon attribution is supplied by platform dev.hardrush.combat.CombatRuntime. */
public record RoundResult(Kind kind, UUID winner, String reason) {
    public enum Kind { COMPLETED, FAILED, ABORTED }
    public RoundResult {
        Objects.requireNonNull(kind); Objects.requireNonNull(reason);
        if (reason.isBlank() || reason.length() > 1024) throw new IllegalArgumentException("Invalid result reason");
        if (kind != Kind.COMPLETED && winner != null) throw new IllegalArgumentException("Only completion has a winner");
    }
}
