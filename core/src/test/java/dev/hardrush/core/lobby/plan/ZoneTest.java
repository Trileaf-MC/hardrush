package dev.hardrush.core.lobby.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ZoneTest {
    /** A 3×3 floor at y=64 around (0, 0). */
    static Zone.Floor square(int y) {
        var cells = new HashSet<Cell>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) cells.add(new Cell(x, y, z));
        return new Zone.Floor(cells);
    }

    @Test void floorEdgesHalfSlabsAndJumps() {
        var floor = square(64);
        assertTrue(floor.contains(-1.0, 65, -1.0, false));
        assertTrue(floor.contains(1.999, 65, 1.999, false));
        assertFalse(floor.contains(2.0, 65, 0.5, false));
        assertFalse(floor.contains(-1.001, 65, 0.5, false));
        assertTrue(floor.contains(0.5, 64.5, 0.5, false), "standing on a bottom slab placed in the floor layer");
        assertTrue(floor.contains(0.5, 66.4, 0.5, false), "mid-jump");
        assertFalse(floor.contains(0.5, 66.6, 0.5, false));
        assertFalse(floor.contains(0.5, 63.9, 0.5, false));
    }
    @Test void stayingAcceptsAnyHitboxCorner() {
        var floor = square(64);
        // Centre 0.2 blocks past the east edge: entering fails, staying still holds.
        assertFalse(floor.contains(2.2, 65, 0.5, false));
        assertTrue(floor.contains(2.2, 65, 0.5, true));
        assertFalse(floor.contains(2.4, 65, 0.5, true));
        var volume = Zone.box(new Cell(0, 64, 0), 1, 2, 1);
        assertFalse(volume.contains(2.2, 65, 0.5, false));
        assertTrue(volume.contains(2.2, 65, 0.5, true));
    }
    @Test void floorsCanStack() {
        var cells = new HashSet<>(square(64).floor());
        cells.add(new Cell(0, 70, 0));
        var floor = new Zone.Floor(cells);
        assertTrue(floor.contains(0.5, 65, 0.5, false));
        assertTrue(floor.contains(0.5, 71, 0.5, false));
        assertFalse(floor.contains(0.5, 68, 0.5, false));
        assertFalse(floor.contains(1.5, 71, 0.5, false));
        assertEquals(new Box(new Cell(-1, 64, -1), new Cell(1, 72, 1)), floor.extent());
        assertEquals(new Zone.Floor(new HashSet<>(cells)), floor);
    }
    @Test void volumeIsCentredOnTheMarker() {
        var volume = Zone.box(new Cell(10, 64, 20), 2, 3, 1);
        assertEquals(new Box(new Cell(8, 64, 19), new Cell(12, 67, 21)), volume.box());
        assertTrue(volume.contains(8.0, 64, 19.0, false));
        assertTrue(volume.contains(12.99, 67.99, 21.99, false));
        assertFalse(volume.contains(13.0, 65, 20, false));
        assertFalse(volume.contains(10, 68, 20, false));
        assertEquals(new Point(10.5, 64, 20.5, 0), volume.center());
    }
    @Test void centres() {
        assertEquals(new Point(0.5, 65, 0.5, 0), square(64).center());
        assertThrows(IllegalStateException.class, () -> new Zone.Floor(Set.of()).center());
    }
    @Test void overlaps() {
        var a = square(64);
        assertTrue(a.overlaps(new Zone.Floor(Set.of(new Cell(1, 66, 1)))));
        assertFalse(a.overlaps(new Zone.Floor(Set.of(new Cell(1, 67, 1)))));
        assertFalse(a.overlaps(new Zone.Floor(Set.of(new Cell(2, 64, 1)))));
        assertTrue(a.overlaps(Zone.box(new Cell(3, 65, 0), 2, 0, 0)));
        assertTrue(Zone.box(new Cell(3, 65, 0), 2, 0, 0).overlaps(a));
        assertFalse(a.overlaps(Zone.box(new Cell(3, 65, 0), 1, 0, 0)));
        assertFalse(a.overlaps(Zone.box(new Cell(0, 66, 0), 1, 0, 1)));
        assertTrue(Zone.box(new Cell(0, 0, 0), 1, 1, 1).overlaps(Zone.box(new Cell(2, 1, 0), 1, 1, 1)));
        assertFalse(Zone.box(new Cell(0, 0, 0), 1, 1, 1).overlaps(Zone.box(new Cell(3, 1, 0), 1, 1, 1)));
    }
    @Test void boxGeometry() {
        var box = Box.of(new Cell(3, 5, -1), new Cell(1, 2, 4));
        assertEquals(new Cell(1, 2, -1), box.min());
        assertEquals(3, box.sizeX()); assertEquals(4, box.sizeY()); assertEquals(6, box.sizeZ());
        assertEquals(new Box(new Cell(0, 2, -1), new Cell(3, 5, 4)), box.union(new Cell(0, 3, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Box(new Cell(1, 0, 0), new Cell(0, 0, 0)));
        assertEquals(new Cell(-1, 64, -2), new Point(-0.5, 64.2, -1.1, 0).cell());
    }
}
