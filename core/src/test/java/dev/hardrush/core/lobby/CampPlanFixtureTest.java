package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.lobby.plan.*;
import org.junit.jupiter.api.Test;

/** The camp fixture is a valid, realistic plan, so the core rules tested against it see the real geometry. */
class CampPlanFixtureTest {
    final LobbyPlan plan = CampPlanFixture.build(8, 4, -8);

    @Test void passesAcceptanceWithoutProblems() {
        assertEquals(java.util.List.of(), LobbyPlanValidator.validate(plan, 256));
    }

    @Test void placesTheCampAtAbsoluteBlockCentres() {
        assertEquals(new Box(new Cell(8 - 32, 3, -8 - 24), new Cell(8 + 31, 8, -8 + 23)), plan.bounds());
        assertEquals(new Point(8.5, 4, -7.5, 180), plan.points(LobbyFunction.LANDING_CENTRAL).getFirst());
        assertEquals(new Point(23.5, 4, -5.5, -90), plan.points(LobbyFunction.LANDING_MONSTER).getFirst());
        assertEquals(new Point(29.5, 4, -18.5, 180), plan.points(LobbyFunction.LANDING_ALTAR).getFirst());
        var rules = plan.triggers(LobbyFunction.RULES).getFirst();
        assertEquals(new Cell(-8, 4, -10), rules.block());
        assertEquals("minecraft:lectern", rules.blockId());
        assertEquals(new Point(-7.5, 4, -9.5, 90), rules.anchor());
        for (var f : new LobbyFunction[]{LobbyFunction.HISTORY, LobbyFunction.PAGE_PREV, LobbyFunction.PAGE_NEXT})
            assertEquals("minecraft:lectern", plan.triggers(f).getFirst().blockId(), f.id());
        assertEquals(new Cell(35, 4, -7), plan.triggers(LobbyFunction.VAULT).getFirst().block());
        assertEquals("minecraft:vault", plan.triggers(LobbyFunction.OMINOUS_VAULT).getFirst().blockId());
        assertEquals(new Point(25.5, 4, -11.5, 0), plan.models(LobbyFunction.ROLE_ZOMBIE).getFirst().point());
        assertEquals(1, plan.models(LobbyFunction.MERCHANT).size());
        assertEquals(java.util.List.of(new Cell(29, 6, -23)), plan.altarLights());
        assertEquals(new Cell(12, 4, -7), plan.texts(LobbyFunction.STATUS).getFirst().sign());
    }

    @Test void zonesMatchTheBuiltCamp() {
        var assembly = (Zone.Floor) plan.zones(LobbyFunction.ASSEMBLY).getFirst();
        assertTrue(assembly.floor().contains(new Cell(8, 3, -24)), "The circle's centre");
        assertTrue(assembly.floor().contains(new Cell(15, 3, -24)), "Radius seven is still wool");
        assertFalse(assembly.floor().contains(new Cell(16, 3, -24)));
        var pad = (Zone.Floor) plan.zones(LobbyFunction.ALTAR_PAD).getFirst();
        assertEquals(8, pad.floor().size());
        assertFalse(pad.floor().contains(new Cell(29, 4, -23)), "The beacon's gold is not a pad cell");
        assertTrue(plan.inZone(LobbyFunction.COURTYARD, 19.5, 4, -16.5, false), "The courtyard's north-west corner");
        assertFalse(plan.inZone(LobbyFunction.COURTYARD, 18.5, 4, -16.5, false));
        assertFalse(plan.inZone(LobbyFunction.COURTYARD, 19.5, 8, -16.5, false), "Above the roofline");
    }

    @Test void displaySlotsKeepTheirRowMajorOrder() {
        var slots = plan.points(LobbyFunction.DISPLAY_SLOT);
        var layout = CampPlanFixture.DISPLAY_SLOTS;
        assertEquals(layout.size(), plan.displayCapacity());
        for (int i = 0; i < slots.size(); i++) {
            assertEquals(8 + layout.get(i)[0] + .5, slots.get(i).x());
            assertEquals(-8 + layout.get(i)[1] + .5, slots.get(i).z());
        }
    }
}
