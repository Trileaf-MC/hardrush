package dev.hardrush.core.lobby.plan;

import static dev.hardrush.core.lobby.plan.LobbyFunction.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class LobbyPlanTest {
    static final Box BOUNDS = new Box(new Cell(-50, 0, -50), new Cell(50, 100, 50));

    /** A mutable plan description; {@link #build()} gives a plan that passes validation. */
    static final class Draft {
        Box bounds = BOUNDS;
        final List<Trigger> triggers = new ArrayList<>();
        final List<Model> models = new ArrayList<>();
        final Map<LobbyFunction, List<Point>> points = new EnumMap<>(LobbyFunction.class);
        final Map<LobbyFunction, List<Zone>> zones = new EnumMap<>(LobbyFunction.class);
        final List<Cell> altarLights = new ArrayList<>();
        final List<TextSlot> texts = new ArrayList<>();

        Draft() {
            zone(ASSEMBLY, Zone.box(new Cell(0, 64, -16), 3, 2, 3));
            zone(COURTYARD, Zone.box(new Cell(20, 64, 10), 5, 3, 5));
            zone(ALTAR_PAD, new Zone.Floor(Set.of(new Cell(20, 64, -15), new Cell(21, 64, -15))));
            point(LANDING_CENTRAL, 0.5, 65, 0.5);
            point(LANDING_MONSTER, 20.5, 65, 10.5);
            for (int i = 0; i < 12; i++) point(DISPLAY_SLOT, 15.5 + i % 4 * 3, 65, 20.5 + i / 4 * 3);
            for (var f : List.of(RULES, HISTORY, PAGE_PREV, PAGE_NEXT)) block(f, -10 - triggers.size(), "minecraft:lectern");
            block(VAULT, 27, "minecraft:vault");
            block(OMINOUS_VAULT, 29, "minecraft:vault");
            for (var f : List.of(ROLE_ZOMBIE, ROLE_SKELETON, ROLE_CREEPER, MERCHANT)) models.add(new Model(f, new Point(17 + 4 * models.size(), 65, -4, 0)));
        }
        void zone(LobbyFunction f, Zone zone) { zones.computeIfAbsent(f, k -> new ArrayList<>()).add(zone); }
        void point(LobbyFunction f, double x, double y, double z) { points.computeIfAbsent(f, k -> new ArrayList<>()).add(new Point(x, y, z, 0)); }
        void block(LobbyFunction f, int x, String id) {
            var cell = new Cell(x, 65, 1);
            triggers.add(new Trigger(f, cell, id, new Point(x + .5, 65, 1.5, 0)));
        }
        LobbyPlan build() { return new LobbyPlan(bounds, triggers, models, points, zones, altarLights, texts); }
        List<Problem> problems() { return LobbyPlanValidator.validate(build(), 256); }
    }

    static List<ProblemKind> kinds(List<Problem> problems) { return problems.stream().map(Problem::kind).toList(); }

    @Test void completePlanHasNoProblems() {
        assertEquals(List.of(), new Draft().problems());
    }
    @Test void displaySlotsAreOrderedByYThenZThenX() {
        var draft = new Draft();
        draft.points.put(DISPLAY_SLOT, new ArrayList<>(List.of(new Point(1, 66, 0, 0), new Point(2, 65, 1, 0), new Point(1, 65, 1, 0), new Point(9, 65, 0, 0))));
        assertEquals(List.of(new Point(9, 65, 0, 0), new Point(1, 65, 1, 0), new Point(2, 65, 1, 0), new Point(1, 66, 0, 0)), draft.build().points(DISPLAY_SLOT));
        assertEquals(4, draft.build().displayCapacity());
    }
    @Test void queriesByFunction() {
        var draft = new Draft();
        draft.altarLights.add(new Cell(20, 70, -15));
        draft.texts.add(new TextSlot(STATUS, new Cell(0, 66, 3), new Point(0.5, 66, 3.5, 0)));
        var plan = draft.build();
        assertEquals(1, plan.count(ALTAR_LIGHT));
        assertEquals(1, plan.count(STATUS));
        assertEquals(1, plan.count(RULES));
        assertEquals(12, plan.count(DISPLAY_SLOT));
        assertEquals(0, plan.count(LANDING_ALTAR));
        assertEquals(List.of(), plan.points(LANDING_ALTAR));
        assertTrue(plan.inZone(ASSEMBLY, 0.5, 64, -16.5, false));
        assertFalse(plan.inZone(ASSEMBLY, 0.5, 64, 0.5, false));
        assertEquals(Optional.of(plan.zones(ALTAR_PAD).getFirst()), plan.zoneAt(ALTAR_PAD, 21.5, 65, -14.5, false));
        assertTrue(plan.zoneAt(ALTAR_PAD, 22.5, 65, -14.5, false).isEmpty());
        var more = plan.withAdditions(List.of(new Trigger(HISTORY, new Cell(2, 65, 2), "minecraft:lectern", new Point(2.5, 65, 2.5, 0))),
                List.of(new Model(MERCHANT, new Point(3, 65, 3, 0))));
        assertEquals(2, more.count(HISTORY));
        assertEquals(2, more.models(MERCHANT).size());
        assertEquals(1, plan.count(HISTORY));
    }
    @Test void constructorRejectsBindingsAFunctionDoesNotHave() {
        var draft = new Draft();
        draft.points.put(ASSEMBLY, List.of(new Point(0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, draft::build);
        var zones = new Draft();
        zones.zones.put(LANDING_CENTRAL, List.of(Zone.box(new Cell(0, 0, 0), 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, zones::build);
        var models = new Draft();
        models.models.add(new Model(RULES, new Point(0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, models::build);
        var lights = new Draft();
        lights.triggers.add(new Trigger(ALTAR_LIGHT, new Cell(0, 0, 0), "minecraft:tinted_glass", new Point(0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, lights::build);
    }

    @Test void missingRequiredFunctions() {
        var draft = new Draft();
        draft.triggers.removeIf(t -> t.function() == HISTORY);
        draft.points.remove(LANDING_CENTRAL);
        var problems = draft.problems();
        assertEquals(List.of(ProblemKind.MISSING, ProblemKind.MISSING), kinds(problems));
        assertTrue(problems.stream().allMatch(p -> p.severity() == Severity.ERROR));
        assertTrue(problems.stream().anyMatch(p -> p.message().equals("缺少【战绩】：至少需要 1 个，现在 0 个")));
        assertEquals(List.of(HISTORY), LobbyPlanValidator.missingWithFallback(problems));
    }
    @Test void countsOutsideTheRange() {
        var draft = new Draft();
        for (int i = 0; i < 16; i++) draft.point(LANDING_CENTRAL, 1.5 + i, 65, 5.5);
        for (int i = 0; i < 5; i++) draft.altarLights.add(new Cell(20, 70 + i, -15));
        var problems = draft.problems();
        assertEquals(List.of(ProblemKind.COUNT, ProblemKind.COUNT), kinds(problems));
        assertEquals(Set.of(LANDING_CENTRAL, ALTAR_LIGHT), Set.of(problems.get(0).function(), problems.get(1).function()));
        assertTrue(LobbyPlanValidator.missingWithFallback(problems).isEmpty());
    }
    @Test void emptyZone() {
        var draft = new Draft();
        draft.zone(COURTYARD, new Zone.Floor(Set.of()));
        assertEquals(List.of(ProblemKind.ZONE), kinds(draft.problems()));
    }
    @Test void assemblyMustNotOverlapAltarPadOrCourtyard() {
        var draft = new Draft();
        draft.zone(COURTYARD, Zone.box(new Cell(0, 64, -12), 1, 1, 1));
        assertEquals(List.of(ProblemKind.ZONE), kinds(draft.problems()));
        var altar = new Draft();
        altar.zone(ALTAR_PAD, new Zone.Floor(Set.of(new Cell(0, 63, -16))));
        assertEquals(List.of(ProblemKind.ZONE), kinds(altar.problems()));
        var apart = new Draft();
        apart.zone(ALTAR_PAD, Zone.box(new Cell(24, 64, 14), 1, 1, 1)); // altar pad may share the courtyard
        assertEquals(List.of(), apart.problems());
    }
    @Test void landingsStayOutOfAssemblyAndAltarPad() {
        var draft = new Draft();
        draft.point(LANDING_MONSTER, 0.5, 64, -16.5);
        draft.point(DISPLAY_SLOT, 20.5, 65, -14.5);
        var problems = draft.problems();
        assertEquals(List.of(ProblemKind.ZONE, ProblemKind.ZONE), kinds(problems));
        assertEquals(List.of(LANDING_MONSTER, DISPLAY_SLOT), problems.stream().map(Problem::function).toList());
    }
    @Test void boundsSizeAndContent() {
        var draft = new Draft();
        assertEquals(List.of(ProblemKind.BOUNDS), kinds(LobbyPlanValidator.validate(draft.build(), 64)));
        draft.block(RULES, 60, "minecraft:lectern");
        draft.texts.add(new TextSlot(CREDITS, null, new Point(-60, 65, 0, 0)));
        draft.zone(COURTYARD, Zone.box(new Cell(48, 64, 0), 5, 1, 1));
        var problems = draft.problems();
        assertEquals(List.of(ProblemKind.BOUNDS, ProblemKind.BOUNDS, ProblemKind.BOUNDS), kinds(problems));
        assertTrue(problems.stream().allMatch(p -> p.where() != null));
    }
    @Test void fewDisplaySlotsWarn() {
        var draft = new Draft();
        draft.points.put(DISPLAY_SLOT, new ArrayList<>(draft.points.get(DISPLAY_SLOT).subList(0, 5)));
        var problems = draft.problems();
        assertEquals(List.of(ProblemKind.CAPACITY), kinds(problems));
        assertEquals(Severity.WARNING, problems.getFirst().severity());
    }
}
