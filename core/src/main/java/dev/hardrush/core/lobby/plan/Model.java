package dev.hardrush.core.lobby.plan;

import java.util.Objects;

/** Where the runtime shows a packet model for {@code function}. */
public record Model(LobbyFunction function, Point point) {
    public Model { Objects.requireNonNull(function); Objects.requireNonNull(point); }
}
