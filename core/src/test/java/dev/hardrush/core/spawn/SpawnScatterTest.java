package dev.hardrush.core.spawn;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.lobby.CampPlanFixture;
import dev.hardrush.core.lobby.plan.*;
import java.util.HashSet;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SpawnScatterTest {
    @Test void vanillaWalkVisitsEverySquareCellOnce() {
        for (int radius : new int[]{0, 1, 2, 10}) {
            var seen = new HashSet<Long>();
            for (var c : SpawnScatter.candidates(radius, new SplittableRandom(radius))) {
                assertTrue(Math.abs(c[0]) <= radius && Math.abs(c[1]) <= radius, "Inside the respawn square");
                assertTrue(seen.add(((long) c[0] << 32) ^ (c[1] & 0xffffffffL)), "No repeated candidate");
            }
            assertEquals((2 * radius + 1) * (2 * radius + 1), seen.size());
        }
        assertEquals(1024, SpawnScatter.candidates(100, new SplittableRandom(1)).length, "Vanilla's absolute attempt cap");
        assertArrayEquals(new int[]{0, 0}, SpawnScatter.candidates(0, new SplittableRandom(3))[0], "Radius 0 is the exact spawn");
    }

    @Test void farRingStaysInsideAndFavoursTheOuterEdgeByArea() {
        var random = new SplittableRandom(42);
        int outerHalf = 0, samples = 20_000;
        for (int i = 0; i < samples; i++) {
            var offset = SpawnScatter.farOffset(random);
            double distance = Math.hypot(offset[0], offset[1]);
            assertTrue(distance >= SpawnScatter.FAR_INNER - 1e-9 && distance <= SpawnScatter.FAR_OUTER + 1e-9);
            if (distance > (SpawnScatter.FAR_INNER + SpawnScatter.FAR_OUTER) / 2.0) outerHalf++;
        }
        // Area-uniform: beyond 564 blocks lies (1000² − 564²) / (1000² − 128²) ≈ 69% of the ring.
        assertTrue(outerHalf > samples * 0.67 && outerHalf < samples * 0.72, "outer half share " + outerHalf);
    }

    @Test void bearingUsesEightCompassPointsAndRoundsToTenBlocks() {
        assertEquals("你在出生点 **北** 方约 **620** 格", SpawnScatter.bearing(0, -618));
        assertEquals("你在出生点 **东** 方约 **300** 格", SpawnScatter.bearing(304, 0));
        assertEquals("你在出生点 **东南** 方约 **140** 格", SpawnScatter.bearing(100, 100));
        assertEquals("你在出生点 **西北** 方约 **140** 格", SpawnScatter.bearing(-100, -100));
        assertEquals("西南", SpawnScatter.direction(-100, 100));
        assertEquals("东北", SpawnScatter.direction(100, -100));
        assertEquals("南", SpawnScatter.direction(0, 500));
        assertEquals("西", SpawnScatter.direction(-500, 1));
    }

    @Test void campScatterKeepsArrivalsInTheirPurposeZone() {
        var plan = CampPlanFixture.build(8, 4, 8);
        var bounds = plan.bounds();
        for (var purpose : Landing.values()) {
            var point = LandingRules.candidates(plan, purpose).getFirst();
            int y = point.cell().y(), cells = 0;
            for (int x = bounds.min().x(); x <= bounds.max().x(); x++) for (int z = bounds.min().z(); z <= bounds.max().z(); z++) {
                if (Math.abs(x + .5 - point.x()) > 10 || Math.abs(z + .5 - point.z()) > 10
                        || !LandingRules.scatterAllowed(plan, purpose, point, x, y, z)) continue;
                cells++;
                // Staying in the circle is the widest test: an arrival must not even graze it.
                boolean inCircle = plan.inZone(LobbyFunction.ASSEMBLY, x + .5, y, z + .5, true);
                assertEquals(purpose == Landing.RESTORE_READY, inCircle, purpose + " at " + x + "," + z);
                var altar = plan.zones(LobbyFunction.ALTAR_PAD).getFirst().center();
                assertFalse(Math.abs(x + .5 - altar.x()) <= 2 && Math.abs(z + .5 - altar.z()) <= 2, "Never on the altar plinth");
                boolean courtyard = plan.inZone(LobbyFunction.COURTYARD, x + .5, y, z + .5, false);
                if (purpose == Landing.MONSTER) assertTrue(courtyard);
                if (purpose == Landing.CENTRAL) assertFalse(courtyard);
                for (var slot : plan.points(LobbyFunction.DISPLAY_SLOT)) assertFalse(slot.cell().equals(new Cell(x, y, z)), "Never inside a target model");
            }
            assertTrue(cells >= 9, purpose + " has room to scatter: " + cells);
        }
    }
}
