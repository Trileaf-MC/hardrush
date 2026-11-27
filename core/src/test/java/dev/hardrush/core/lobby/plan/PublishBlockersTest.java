package dev.hardrush.core.lobby.plan;

import static dev.hardrush.core.lobby.plan.LobbyFunction.*;
import static dev.hardrush.core.lobby.plan.ProblemKind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PublishBlockersTest {
    private static final Cell AT = new Cell(1, 64, 2);

    @Test void anEmptyPlanNeedsAMarkerForEveryRequiredFunction() {
        var problems = LobbyPlanValidator.validate(new LobbyPlan(new Box(new Cell(0, 0, 0), new Cell(8, 8, 8)), List.of(), List.of(), Map.of(), Map.of(), List.of(), List.of()), 256);
        var blocking = problems.stream().filter(p -> p.severity() == Severity.ERROR).toList();
        var needs = PublishBlockers.needs(blocking, f -> 0);
        var functions = needs.stream().map(PublishBlockers.Need::function).toList();
        for (var f : LobbyFunction.values())
            assertEquals(f.required(), functions.contains(f), f.id());
        var central = needs.stream().filter(n -> n.function() == LANDING_CENTRAL).findFirst().orElseThrow();
        assertEquals(new PublishBlockers.Need(LANDING_CENTRAL, Binding.POINT, 1, false), central);
        assertEquals(new PublishBlockers.Need(ASSEMBLY, Binding.ZONE, 1, false), find(needs, ASSEMBLY));
        assertEquals(new PublishBlockers.Need(MERCHANT, Binding.MODEL, 1, false), find(needs, MERCHANT));
        assertEquals(new PublishBlockers.Need(RULES, Binding.CLICK, 1, false), find(needs, RULES));
        assertEquals(new PublishBlockers.Need(OMINOUS_VAULT, Binding.BLOCK, 1, true), find(needs, OMINOUS_VAULT), "Vaults come with their block");
    }

    @Test void tooFewCountsWhatIsMissingAndTooManyNeedsNothing() {
        var few = Problem.error(COUNT, DISPLAY_SLOT, null, "");
        assertEquals(List.of(), PublishBlockers.needs(List.of(few), f -> 1), "At the minimum nothing is missing");
        var many = Problem.error(COUNT, STATUS, null, "");
        assertEquals(List.of(), PublishBlockers.needs(List.of(many), f -> 9), "Too many is not fixed by handing out more");
    }

    @Test void aWrongBlockNeedsTheRightOneOnly() {
        var vault = Problem.error(BLOCK, VAULT, AT, "");
        assertEquals(List.of(new PublishBlockers.Need(VAULT, Binding.BLOCK, 0, true)), PublishBlockers.needs(List.of(vault), f -> 1));
        var sign = Problem.error(BLOCK, STATUS, AT, "");
        assertEquals(List.of(new PublishBlockers.Need(STATUS, Binding.TEXT, 0, true)), PublishBlockers.needs(List.of(sign), f -> 1), "Text needs a sign");
        var click = Problem.error(BLOCK, RULES, AT, "");
        assertEquals(List.of(), PublishBlockers.needs(List.of(click), f -> 1), "Any block takes a click binding");
    }

    @Test void problemsNoItemFixesAndWarningsNeedNothing() {
        var problems = List.of(
                Problem.error(ZONE, ASSEMBLY, AT, ""), Problem.error(STANDING, LANDING_CENTRAL, AT, ""),
                Problem.error(MARKER, null, AT, ""), Problem.error(BOUNDS, null, null, ""),
                Problem.warning(MISSING, LANDING_ALTAR, null, ""));
        assertEquals(List.of(), PublishBlockers.needs(problems, f -> 0));
    }

    @Test void oneNeedPerFunction() {
        var problems = List.of(Problem.error(MISSING, VAULT, null, ""), Problem.error(BLOCK, VAULT, AT, ""));
        assertEquals(List.of(new PublishBlockers.Need(VAULT, Binding.BLOCK, 1, true)), PublishBlockers.needs(problems, f -> 0));
    }

    @Test void theSummaryNamesTheFirstFewAndCountsAll() {
        var problems = List.of(Problem.error(MISSING, LANDING_CENTRAL, null, ""), Problem.error(MISSING, RULES, null, ""),
                Problem.error(BLOCK, VAULT, AT, ""), Problem.error(BLOCK, VAULT, new Cell(5, 64, 5), ""),
                Problem.error(MARKER, null, AT, ""));
        assertEquals("缺【中央落点】、缺【规则】、【试炼宝库】方块不对 等 5 项", PublishBlockers.summary(problems, 3));
        assertEquals("缺【中央落点】、缺【规则】、【试炼宝库】方块不对、标记有误", PublishBlockers.summary(problems, 4),
                "Repeated labels are named once; nothing left unnamed, so no count");
    }

    @Test void chatFoldsEveryMissingFunctionIntoOneLine() {
        var problems = LobbyPlanValidator.validate(new LobbyPlan(new Box(new Cell(0, 0, 0), new Cell(8, 8, 8)), List.of(), List.of(),
                Map.of(), Map.of(), List.of(), List.of()), 256).stream().filter(p -> p.severity() == Severity.ERROR).toList();
        var lines = PublishBlockers.chatLines(problems, 6);
        long required = Arrays.stream(LobbyFunction.values()).filter(LobbyFunction::required).count();
        assertEquals(1, lines.size(), "An empty draft reads as one line: " + lines);
        assertTrue(lines.getFirst().startsWith("缺 " + required + " 个功能：集结区、怪物庭院"), lines.getFirst());
    }

    @Test void otherProblemsKeepALineEachUpToTheLimit() {
        var problems = new ArrayList<Problem>();
        problems.add(Problem.error(MISSING, RULES, null, "缺规则"));
        for (int i = 0; i < 8; i++) problems.add(Problem.error(BLOCK, VAULT, new Cell(i, 64, 0), "宝库 " + i));
        problems.add(Problem.error(MISSING, HISTORY, null, "缺战绩"));
        var lines = PublishBlockers.chatLines(problems, 4);
        assertEquals(List.of("缺 2 个功能：规则、战绩", "宝库 0", "宝库 1", "宝库 2", "…还有 5 项，工具箱的验收清单里能逐条传送过去"), lines);
        assertEquals(List.of("宝库 0"), PublishBlockers.chatLines(List.of(problems.get(1)), 4), "No missing line when nothing is missing");
    }

    @Test void everyFunctionHasAPreferredBinding() {
        for (var f : LobbyFunction.values()) assertTrue(f.bindings().contains(PublishBlockers.preferred(f)), f.id());
    }

    private static PublishBlockers.Need find(List<PublishBlockers.Need> needs, LobbyFunction f) {
        return needs.stream().filter(n -> n.function() == f).findFirst().orElseThrow();
    }
}
