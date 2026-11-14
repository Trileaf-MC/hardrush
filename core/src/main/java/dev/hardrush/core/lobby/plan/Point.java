package dev.hardrush.core.lobby.plan;

/** An entity's feet position and facing, in absolute world coordinates. */
public record Point(double x, double y, double z, float yaw) {
    /** The block the feet are inside. */
    public Cell cell() { return new Cell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)); }
}
