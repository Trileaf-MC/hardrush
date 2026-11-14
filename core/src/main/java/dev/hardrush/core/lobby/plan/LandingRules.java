package dev.hardrush.core.lobby.plan;

import java.util.*;

/** Where each {@link Landing} may put a player, as pure plan geometry; standing safety is the platform's job. */
public final class LandingRules {
    /** Restore candidates: the assembly cells nearest the zone centres. */
    static final int RESTORE_CANDIDATES = 9;
    /** Only plan features this close vertically to the standing cell matter, so multi-storey lobbies work. */
    static final int LAYER_REACH = 2;

    /**
     * The exact landings for {@code purpose}. Restore landings stand on the assembly floor cells nearest
     * their zone's centre (floor zones only; a volume has no known floor), nearest first.
     */
    public static List<Point> candidates(LobbyPlan plan, Landing purpose) {
        return switch (purpose) {
            case CENTRAL -> plan.points(LobbyFunction.LANDING_CENTRAL);
            case MONSTER -> plan.points(LobbyFunction.LANDING_MONSTER);
            case ALTAR -> plan.points(LobbyFunction.LANDING_ALTAR).isEmpty()
                    ? plan.points(LobbyFunction.LANDING_MONSTER) : plan.points(LobbyFunction.LANDING_ALTAR);
            case RESTORE_READY -> restoreCandidates(plan);
        };
    }

    private static List<Point> restoreCandidates(LobbyPlan plan) {
        record Ranked(Point point, double distance) {}
        var ranked = new ArrayList<Ranked>();
        for (var zone : plan.zones(LobbyFunction.ASSEMBLY)) {
            if (!(zone instanceof Zone.Floor floor) || floor.isEmpty()) continue;
            var center = floor.center();
            for (var c : floor.floor()) {
                var stand = new Point(c.x() + .5, c.y() + 1, c.z() + .5, 0);
                double dx = stand.x() - center.x(), dy = stand.y() - center.y(), dz = stand.z() - center.z();
                ranked.add(new Ranked(stand, Math.sqrt(dx * dx + dy * dy + dz * dz)));
            }
        }
        // Ties break by position so the order never depends on set iteration.
        ranked.sort(Comparator.comparingDouble(Ranked::distance).thenComparingDouble(r -> r.point().y())
                .thenComparingDouble(r -> r.point().z()).thenComparingDouble(r -> r.point().x()));
        return ranked.stream().limit(RESTORE_CANDIDATES).map(Ranked::point).toList();
    }

    /**
     * Whether a scatter around {@code chosen} may stand a {@code purpose} arrival in block {@code (x, y, z)}
     * (the feet block). Every purpose keeps clear of facilities, target slots and the altar plinth; only the
     * restore landing may enter the assembly zone (and then wholly inside it), so an arrival never prepares
     * or cancels by accident.
     */
    public static boolean scatterAllowed(LobbyPlan plan, Landing purpose, Point chosen, int x, int y, int z) {
        var bounds = plan.bounds();
        if (x <= bounds.min().x() || x >= bounds.max().x() || z <= bounds.min().z() || z >= bounds.max().z()) return false;
        for (var t : plan.triggers()) {
            if (t.block() != null && near(t.block(), x, y, z, 1)) return false;
            if (near(t.anchor().cell(), x, y, z, 1)) return false;
        }
        for (var m : plan.models()) if (near(m.point().cell(), x, y, z, 1)) return false;
        for (var p : plan.points(LobbyFunction.DISPLAY_SLOT)) if (near(p.cell(), x, y, z, 1)) return false;
        for (var zone : plan.zones(LobbyFunction.ALTAR_PAD))
            if (zone instanceof Zone.Floor floor && floor.floor().stream().anyMatch(c -> near(c, x, y, z, 2))) return false;
        if (purpose != Landing.RESTORE_READY && !clearOfAssembly(plan, x, y, z)) return false;
        return switch (purpose) {
            case CENTRAL -> !inZone(plan, LobbyFunction.COURTYARD, x, y, z);
            case MONSTER -> inZone(plan, LobbyFunction.COURTYARD, x, y, z);
            case ALTAR -> Math.abs(x - chosen.cell().x()) <= 2 && Math.abs(z - chosen.cell().z()) <= 2;
            case RESTORE_READY -> whollyInAssembly(plan, x, y, z);
        };
    }

    private static boolean whollyInAssembly(LobbyPlan plan, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (!inZone(plan, LobbyFunction.ASSEMBLY, x + dx, y, z + dz)) return false;
        return true;
    }
    /** Outside every assembly zone, and more than two blocks (horizontally) from any of its floor cells. */
    private static boolean clearOfAssembly(LobbyPlan plan, int x, int y, int z) {
        if (inZone(plan, LobbyFunction.ASSEMBLY, x, y, z)) return false;
        for (var zone : plan.zones(LobbyFunction.ASSEMBLY)) {
            if (!(zone instanceof Zone.Floor floor)) continue;
            for (var c : floor.floor())
                if (Math.abs(c.y() - y) <= LAYER_REACH && Math.hypot(c.x() - x, c.z() - z) <= 2) return false;
        }
        return true;
    }
    private static boolean inZone(LobbyPlan plan, LobbyFunction f, int x, int y, int z) {
        return plan.inZone(f, x + .5, y, z + .5, false);
    }
    /** Horizontal Chebyshev distance within {@code reach}, on a nearby layer. */
    private static boolean near(Cell c, int x, int y, int z, int reach) {
        return Math.abs(c.y() - y) <= LAYER_REACH && Math.abs(c.x() - x) <= reach && Math.abs(c.z() - z) <= reach;
    }
    private LandingRules() {}
}
