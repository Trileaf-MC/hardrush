package dev.hardrush.core.lobby.plan;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.lobby.CampPlanFixture;
import java.util.*;
import org.junit.jupiter.api.Test;

class FallbackSitesTest {
    private static final Point CENTER = new Point(.5, 65, .5, 180);

    @Test void candidatesRunNearestFirstWithinTheRing() {
        var cells = FallbackSites.candidates(CENTER);
        var origin = CENTER.cell();
        int last = -1;
        for (var c : cells) {
            int d = (c.x() - origin.x()) * (c.x() - origin.x()) + (c.z() - origin.z()) * (c.z() - origin.z());
            assertTrue(d >= 4 && d <= 64, c.toString());
            assertTrue(d >= last, "nearest first");
            assertEquals(65, c.y(), "same layer as the landing");
            last = d;
        }
        // Ties break by z, then x: the first ring cell is due north.
        assertEquals(new Cell(0, 65, -2), cells.getFirst());
        assertEquals(cells.size(), new HashSet<>(cells).size());
    }

    @Test void theNearestFreeCellOutsideZonesAndClearOfFacilitiesWins() {
        var plan = CampPlanFixture.build(0, 65, 0);
        var site = FallbackSites.find(plan, CENTER, List.of(), cell -> true).orElseThrow();
        assertEquals(new Cell(0, 65, -2), site);

        // A cell the world says is blocked is skipped; so is one next to an earlier stand-in.
        var blocked = FallbackSites.find(plan, CENTER, List.of(), cell -> !cell.equals(site)).orElseThrow();
        assertNotEquals(site, blocked);
        var next = FallbackSites.find(plan, CENTER, List.of(site), cell -> true).orElseThrow();
        int dx = next.x() - site.x(), dz = next.z() - site.z();
        assertTrue(dx * dx + dz * dz >= 4, "keeps 2 blocks from the earlier stand-in: " + next);
    }

    @Test void zonesAndOccupiedCellsAreAvoided() {
        // A central landing right beside the assembly circle (centre 0,-16, radius 7) and the status sign.
        var plan = CampPlanFixture.build(0, 65, 0);
        var center = new Point(.5, 65, -7.5, 0);
        var site = FallbackSites.find(plan, center, List.of(), cell -> true).orElseThrow();
        assertFalse(plan.inZone(LobbyFunction.ASSEMBLY, site.x() + .5, site.y(), site.z() + .5, false), "not in the assembly: " + site);
        for (var taken : FallbackSites.occupied(plan)) {
            int dx = taken.x() - site.x(), dy = taken.y() - site.y(), dz = taken.z() - site.z();
            assertTrue(dx * dx + dy * dy + dz * dz >= 4, "clear of " + taken + ": " + site);
        }
    }

    @Test void nothingFreeMeansNoSite() {
        var plan = CampPlanFixture.build(0, 65, 0);
        assertTrue(FallbackSites.find(plan, CENTER, List.of(), cell -> false).isEmpty());
    }

    @Test void facingLooksTowardTheTarget() {
        var cell = new Cell(0, 65, 0);
        assertEquals(0f, FallbackSites.facing(cell, new Point(.5, 65, 5.5, 0)), 1e-4, "south");
        assertEquals(90f, Math.abs(FallbackSites.facing(cell, new Point(-4.5, 65, .5, 0))), 1e-4, "west");
        assertEquals(-90f, FallbackSites.facing(cell, new Point(5.5, 65, .5, 0)), 1e-4, "east");
        assertEquals(180f, Math.abs(FallbackSites.facing(cell, new Point(.5, 65, -4.5, 0))), 1e-4, "north");
    }
}
