package dev.hardrush.core.protection;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProtectionBoundaryTest {
    private final ProtectionBoundary boundary = new ProtectionBoundary(.5, .5, 32);

    @Test void fullRadiusIncludesEdgeAndRejectsDiagonalSquareCorners() {
        assertTrue(boundary.contains(32.5, .5));
        assertFalse(boundary.contains(32.5001, .5));
        assertTrue(boundary.contains(-31.5, .5));
        assertFalse(boundary.contains(32, 32));
    }

    @Test void fastCrossingsCannotTunnelBetweenOutsideEndpoints() {
        assertTrue(boundary.crosses(-40, .5, 40, .5));
        assertTrue(boundary.crosses(-40, 32.5, 40, 32.5));
        assertFalse(boundary.crosses(-40, 33, 40, 33));
        assertFalse(boundary.crosses(40, .5, 50, .5));
        assertTrue(boundary.crosses(.5, .5, .5, .5));
    }

    @Test void blockIntersectionProtectsBoundaryColumnsAndNegativeCoordinates() {
        assertTrue(boundary.intersectsBlock(32, 0));
        assertTrue(boundary.intersectsBlock(-32, 0));
        assertFalse(boundary.intersectsBlock(33, 0));
        assertFalse(boundary.intersectsBlock(32, 32));
    }

    @Test void invalidGeometryFailsClosedAtConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ProtectionBoundary(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProtectionBoundary(Double.NaN, 0, 32));
    }
}
