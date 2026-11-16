package dev.hardrush.core.lobby.plan;

import java.util.*;

/**
 * Where stand-in facilities go (design 10.4): on the first central landing's layer, nearest first within radius
 * 2–8, on a cell whose own block and the one above are free and that rests on a full block, outside every zone,
 * inside the lobby's bounds and at least 2 blocks from every landing, facility and earlier stand-in.
 */
public final class FallbackSites {
    public static final int MIN_RADIUS = 2, MAX_RADIUS = 8;
    /** Squared distance a stand-in keeps from everything else. */
    private static final int CLEARANCE_SQR = 4;

    /** The world's answer for one cell: it and the block above are air, and the block below is full and solid. */
    @FunctionalInterface
    public interface Ground { boolean free(Cell cell); }

    private FallbackSites() {}

    /** Every cell within the ring around {@code center}'s cell, nearest first; ties go by z, then x. */
    public static List<Cell> candidates(Point center) {
        var origin = center.cell();
        var cells = new ArrayList<Cell>();
        for (int dz = -MAX_RADIUS; dz <= MAX_RADIUS; dz++)
            for (int dx = -MAX_RADIUS; dx <= MAX_RADIUS; dx++) {
                int d = dx * dx + dz * dz;
                if (d >= MIN_RADIUS * MIN_RADIUS && d <= MAX_RADIUS * MAX_RADIUS) cells.add(origin.offset(dx, 0, dz));
            }
        cells.sort(Comparator.<Cell>comparingInt(c -> sqr(c.x() - origin.x()) + sqr(c.z() - origin.z()))
                .thenComparingInt(Cell::z).thenComparingInt(Cell::x));
        return List.copyOf(cells);
    }

    /** Every cell already taken by a landing, display slot, model, trigger, text slot or altar light. */
    public static List<Cell> occupied(LobbyPlan plan) {
        var taken = new ArrayList<Cell>();
        plan.points().values().forEach(points -> points.forEach(p -> taken.add(p.cell())));
        plan.models().forEach(m -> taken.add(m.point().cell()));
        plan.triggers().forEach(t -> taken.add(t.block() != null ? t.block() : t.anchor().cell()));
        plan.texts().forEach(t -> taken.add(t.sign() != null ? t.sign() : t.anchor().cell()));
        taken.addAll(plan.altarLights());
        return taken;
    }

    /** The nearest acceptable cell, keeping clear of {@code taken} as well as of the plan's own facilities. */
    public static Optional<Cell> find(LobbyPlan plan, Point center, Collection<Cell> taken, Ground ground) {
        var avoid = new ArrayList<>(occupied(plan));
        avoid.addAll(taken);
        for (var cell : candidates(center)) {
            if (!plan.bounds().contains(cell) || !plan.bounds().contains(cell.offset(0, 1, 0))) continue;
            if (inAnyZone(plan, cell)) continue;
            if (avoid.stream().anyMatch(o -> sqr(o.x() - cell.x()) + sqr(o.y() - cell.y()) + sqr(o.z() - cell.z()) < CLEARANCE_SQR)) continue;
            if (ground.free(cell)) return Optional.of(cell);
        }
        return Optional.empty();
    }

    /** The yaw that looks from {@code from}'s centre toward {@code to}, in Minecraft's convention (0 = south, 90 = west). */
    public static float facing(Cell from, Point to) {
        double dx = to.x() - (from.x() + .5), dz = to.z() - (from.z() + .5);
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static boolean inAnyZone(LobbyPlan plan, Cell cell) {
        for (var zones : plan.zones().values())
            for (var zone : zones) if (zone.contains(cell.x() + .5, cell.y(), cell.z() + .5, false)) return true;
        return false;
    }
    private static int sqr(int v) { return v * v; }
}
