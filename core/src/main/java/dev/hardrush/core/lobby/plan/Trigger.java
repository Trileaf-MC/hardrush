package dev.hardrush.core.lobby.plan;

import java.util.Objects;

/**
 * A click or block binding. {@code block} and {@code blockId} (the expected block) are null for entity
 * triggers, which the runtime finds by tag. {@code anchor} is where hints point.
 */
public record Trigger(LobbyFunction function, Cell block, String blockId, Point anchor) {
    public Trigger {
        Objects.requireNonNull(function); Objects.requireNonNull(anchor);
        if ((block == null) != (blockId == null)) throw new IllegalArgumentException("block and blockId go together");
    }
}
