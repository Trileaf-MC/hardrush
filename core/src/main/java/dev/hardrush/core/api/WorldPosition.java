package dev.hardrush.core.api;

import java.util.Objects;

/** A world key and coordinates, with no Minecraft registry/entity references. */
public record WorldPosition(String worldKey, double x, double y, double z, float yaw, float pitch) {
    public WorldPosition {
        Objects.requireNonNull(worldKey, "worldKey");
        if (worldKey.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90) {
            throw new IllegalArgumentException("Invalid world position");
        }
    }
}
