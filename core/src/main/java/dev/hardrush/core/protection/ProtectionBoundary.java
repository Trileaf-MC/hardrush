package dev.hardrush.core.protection;

/** Full-height circular spawn boundary. Coordinates are block-center world coordinates. */
public record ProtectionBoundary(double centerX, double centerZ, double radius) {
    public ProtectionBoundary {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !Double.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("Invalid protection boundary");
        }
    }

    public boolean contains(double x, double z) {
        double dx = x - centerX, dz = z - centerZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Includes a fast movement passing entirely through the zone between two outside positions. */
    public boolean crosses(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX, dz = toZ - fromZ;
        double length = dx * dx + dz * dz;
        if (length == 0) return contains(fromX, fromZ);
        double fraction = Math.clamp(((centerX - fromX) * dx + (centerZ - fromZ) * dz) / length, 0, 1);
        return contains(fromX + fraction * dx, fromZ + fraction * dz);
    }

    /** A block is protected if any horizontal part intersects the circle, avoiding half-block gaps. */
    public boolean intersectsBlock(int x, int z) {
        return contains(Math.clamp(centerX, x, x + 1d), Math.clamp(centerZ, z, z + 1d));
    }
}
