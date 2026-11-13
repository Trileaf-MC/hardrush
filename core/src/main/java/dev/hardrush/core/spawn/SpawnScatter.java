package dev.hardrush.core.spawn;

import java.util.random.RandomGenerator;

/** Platform-neutral landing geometry: vanilla's spawn-radius walk, the far-landing ring, and a bearing caption. */
public final class SpawnScatter {
    /** Mid-round admissions land in this ring around the round's natural spawn, uniformly by area. */
    public static final int FAR_INNER = 128, FAR_OUTER = 1000;
    /** Vanilla PlayerSpawnFinder.ABSOLUTE_MAX_ATTEMPTS. */
    private static final int MAX_CANDIDATES = 1024;
    private static final String[] DIRECTIONS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

    /**
     * The {@code (dx, dz)} offsets vanilla's PlayerSpawnFinder tries for {@code respawn_radius = radius}:
     * a random start, then a coprime stride across the (2r+1)² square, capped at 1024 candidates.
     */
    public static int[][] candidates(int radius, RandomGenerator random) {
        radius = Math.max(0, radius);
        long side = radius * 2L + 1;
        int count = (int) Math.min(MAX_CANDIDATES, side * side);
        int coprime = count <= 16 ? count - 1 : 17;
        int offset = random.nextInt(count);
        int[][] result = new int[count][];
        for (int i = 0; i < count; i++) {
            int value = (int) ((offset + (long) coprime * i) % count);
            result[i] = new int[]{value % (radius * 2 + 1) - radius, value / (radius * 2 + 1) - radius};
        }
        return result;
    }

    /** A point of the far ring, uniform by area: most landings fall toward the outer edge. */
    public static double[] farOffset(RandomGenerator random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = Math.sqrt(random.nextDouble() * ((double) FAR_OUTER * FAR_OUTER - (double) FAR_INNER * FAR_INNER)
                + (double) FAR_INNER * FAR_INNER);
        return new double[]{Math.cos(angle) * distance, Math.sin(angle) * distance};
    }

    /** Where {@code (dx, dz)} lies from the spawn in eight compass points; +x is east and +z is south. */
    public static String direction(double dx, double dz) {
        double clockwiseFromNorth = Math.toDegrees(Math.atan2(dx, -dz));
        return DIRECTIONS[Math.floorMod((int) Math.round(clockwiseFromNorth / 45), 8)];
    }

    /** The arrival caption, distance rounded to ten blocks. */
    public static String bearing(double dx, double dz) {
        long distance = Math.round(Math.hypot(dx, dz) / 10) * 10;
        return "你在出生点 **" + direction(dx, dz) + "** 方约 **" + distance + "** 格";
    }

    private SpawnScatter() {}
}
