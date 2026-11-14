package dev.hardrush.core.lobby.plan;

/** An inclusive block range. Continuous coordinates cover each edge block wholly: {@code min <= v < max + 1}. */
public record Box(Cell min, Cell max) {
    public Box {
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
            throw new IllegalArgumentException("Box min must not exceed max: " + min + " " + max);
    }
    public static Box of(Cell a, Cell b) {
        return new Box(new Cell(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
                new Cell(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
    }
    public boolean contains(Cell c) {
        return c.x() >= min.x() && c.x() <= max.x() && c.y() >= min.y() && c.y() <= max.y() && c.z() >= min.z() && c.z() <= max.z();
    }
    public boolean contains(double x, double y, double z) {
        return x >= min.x() && x < max.x() + 1 && y >= min.y() && y < max.y() + 1 && z >= min.z() && z < max.z() + 1;
    }
    public int sizeX() { return max.x() - min.x() + 1; }
    public int sizeY() { return max.y() - min.y() + 1; }
    public int sizeZ() { return max.z() - min.z() + 1; }
    public Box union(Box other) { return new Box(of(min, other.min).min(), of(max, other.max).max()); }
    public Box union(Cell c) { return union(new Box(c, c)); }
    public boolean intersects(Box other) {
        return min.x() <= other.max.x() && other.min.x() <= max.x() && min.y() <= other.max.y() && other.min.y() <= max.y()
                && min.z() <= other.max.z() && other.min.z() <= max.z();
    }
}
