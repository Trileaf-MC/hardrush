package dev.hardrush.core.lobby.plan;

/** A block position in absolute world coordinates. */
public record Cell(int x, int y, int z) {
    public Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
}
