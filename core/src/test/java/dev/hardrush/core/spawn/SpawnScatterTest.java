package dev.hardrush.core.spawn;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.lobby.LobbyLayout;
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
        for (var purpose : LobbyLayout.Landing.values()) {
            var point = LobbyLayout.landing(purpose);
            int cells = 0;
            for (int x = LobbyLayout.MIN_X; x <= LobbyLayout.MAX_X; x++) for (int z = LobbyLayout.MIN_Z; z <= LobbyLayout.MAX_Z; z++) {
                if (Math.abs(x - point.x()) > 10 || Math.abs(z - point.z()) > 10 || !LobbyLayout.scatterAllowed(purpose, x, z)) continue;
                cells++;
                boolean inCircle = Math.hypot(x - LobbyLayout.ASSEMBLY.x(), z - LobbyLayout.ASSEMBLY.z()) <= LobbyLayout.ASSEMBLY.radius() + .35;
                assertEquals(purpose == LobbyLayout.Landing.RESTORE_READY, inCircle, purpose + " at " + x + "," + z);
                var altar = LobbyLayout.facility(LobbyLayout.Facility.ALTAR);
                assertFalse(Math.abs(x - altar.x()) <= 2 && Math.abs(z - altar.z()) <= 2, "Never on the altar plinth");
                if (purpose == LobbyLayout.Landing.MONSTER) assertTrue(LobbyLayout.MONSTER_CAMP.contains(x, z));
                if (purpose == LobbyLayout.Landing.CENTRAL) assertFalse(LobbyLayout.MONSTER_CAMP.contains(x, z));
                for (var slot : LobbyLayout.displaySlots()) assertFalse(slot.x() == x && slot.z() == z, "Never inside a target model");
            }
            assertTrue(cells >= 9, purpose + " has room to scatter: " + cells);
        }
    }
}
