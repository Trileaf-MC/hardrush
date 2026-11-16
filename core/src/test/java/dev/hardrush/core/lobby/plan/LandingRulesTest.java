package dev.hardrush.core.lobby.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class LandingRulesTest {
    /** Every standing point of {@code box}, nearest its centre first: the slow reference. */
    static List<Point> bruteForce(Zone.Volume volume) {
        var box = volume.box();
        var center = volume.center();
        var all = new ArrayList<Point>();
        for (int y = box.min().y(); y <= box.max().y(); y++)
            for (int z = box.min().z(); z <= box.max().z(); z++)
                for (int x = box.min().x(); x <= box.max().x(); x++) all.add(new Point(x + .5, y, z + .5, 0));
        Comparator<Point> order = Comparator.comparingDouble((Point p) -> distance(p, center))
                .thenComparingDouble(Point::y).thenComparingDouble(Point::z).thenComparingDouble(Point::x);
        all.sort(order);
        return all.subList(0, Math.min(LandingRules.RESTORE_CANDIDATES, all.size()));
    }
    static double distance(Point a, Point b) { return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2)); }

    static LobbyPlan withAssembly(Zone.Volume volume) {
        var draft = new LobbyPlanTest.Draft();
        draft.bounds = new Box(new Cell(-200, -64, -200), new Cell(200, 300, 200));
        draft.zones.put(LobbyFunction.ASSEMBLY, List.of(volume));
        return draft.build();
    }

    @Test void boxOnlyAssemblyHasRestoreLandingsInsideItNearestFirst() {
        var volume = Zone.box(new Cell(0, 64, -16), 3, 2, 3);
        var plan = withAssembly(volume);
        var candidates = LandingRules.candidates(plan, Landing.RESTORE_READY);
        assertEquals(LandingRules.RESTORE_CANDIDATES, candidates.size());
        for (var p : candidates) assertTrue(volume.contains(p.x(), p.y(), p.z(), false), p.toString());
        for (int i = 1; i < candidates.size(); i++)
            assertTrue(distance(candidates.get(i - 1), volume.center()) <= distance(candidates.get(i), volume.center()));
        assertEquals(new Point(0.5, 64, -15.5, 0), candidates.getFirst());
        assertEquals(bruteForce(volume), candidates);
    }

    @Test void matchesTheReferenceForLargeThinAndEvenBoxes() {
        for (var volume : List.of(Zone.box(new Cell(0, 64, 0), 64, 64, 64),
                new Zone.Volume(new Box(new Cell(3, 70, -9), new Cell(4, 70, -8))),
                new Zone.Volume(new Box(new Cell(0, 10, 0), new Cell(0, 40, 0))),
                new Zone.Volume(new Box(new Cell(-6, 0, 1), new Cell(5, 3, 30)))))
            assertEquals(bruteForce(volume), LandingRules.candidates(withAssembly(volume), Landing.RESTORE_READY), volume.toString());
    }
}
