package dev.hardrush.core.lobby.plan;

import java.util.Objects;

/** A sign the mod writes into; {@code sign} is null for text display entities, which the runtime finds by tag. */
public record TextSlot(LobbyFunction function, Cell sign, Point anchor) {
    public TextSlot { Objects.requireNonNull(function); Objects.requireNonNull(anchor); }
}
