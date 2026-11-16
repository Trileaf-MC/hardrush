package dev.hardrush.core.lobby.plan;

import static dev.hardrush.core.lobby.plan.LobbyFunction.*;
import static dev.hardrush.core.lobby.plan.ProblemKind.*;

import java.util.*;

/**
 * The acceptance rules that need no world: counts, zone geometry and bounds. Block, standing and marker
 * checks need the world and live in the platform.
 */
public final class LobbyPlanValidator {
    /** Below this many display slots a full round's targets no longer fit on one page. */
    public static final int COMFORTABLE_DISPLAY_SLOTS = 10;
    private static final List<LobbyFunction> LANDINGS = List.of(LANDING_CENTRAL, LANDING_MONSTER, LANDING_ALTAR, DISPLAY_SLOT);
    private static final List<LobbyFunction> KEEP_CLEAR = List.of(ASSEMBLY, ALTAR_PAD);

    public static List<Problem> validate(LobbyPlan plan, int maxHorizontalSize) {
        var problems = new ArrayList<Problem>();
        counts(plan, problems);
        zones(plan, problems);
        bounds(plan, maxHorizontalSize, problems);
        int slots = plan.displayCapacity();
        if (slots > 0 && slots < COMFORTABLE_DISPLAY_SLOTS)
            problems.add(Problem.warning(CAPACITY, DISPLAY_SLOT, null,
                    "【" + DISPLAY_SLOT.displayName() + "】只有 " + slots + " 个，建议至少 " + COMFORTABLE_DISPLAY_SLOTS + " 个，人多时目标会分好几页"));
        return List.copyOf(problems);
    }

    /** Functions whose absence installing covers with a stand-in facility. */
    public static List<LobbyFunction> missingWithFallback(List<Problem> problems) {
        return problems.stream().filter(p -> p.kind() == MISSING && p.function() != null && p.function().fallback() != Fallback.NONE)
                .map(Problem::function).distinct().toList();
    }

    private static void counts(LobbyPlan plan, List<Problem> problems) {
        for (var f : LobbyFunction.values()) {
            int n = plan.count(f);
            if (n == 0 && f.required())
                problems.add(Problem.error(MISSING, f, null, "缺少【" + f.displayName() + "】：至少需要 " + f.min() + " 个，现在 0 个"));
            else if (n < f.min() || n > f.max())
                problems.add(Problem.error(COUNT, f, null, "【" + f.displayName() + "】数量不对：" + range(f) + "，现在 " + n + " 个"));
        }
    }
    private static String range(LobbyFunction f) {
        if (f.max() == Integer.MAX_VALUE) return "至少需要 " + f.min() + " 个";
        return f.min() == 0 ? "最多 " + f.max() + " 个" : "需要 " + f.min() + "–" + f.max() + " 个";
    }

    private static void zones(LobbyPlan plan, List<Problem> problems) {
        for (var entry : plan.zones().entrySet())
            for (var zone : entry.getValue())
                if (zone instanceof Zone.Floor floor && floor.isEmpty())
                    problems.add(Problem.error(ZONE, entry.getKey(), null, "【" + entry.getKey().displayName() + "】有一块区域是空的，标记下面没有地板"));
        for (var other : List.of(ALTAR_PAD, COURTYARD))
            for (var a : nonEmpty(plan, ASSEMBLY))
                for (var b : nonEmpty(plan, other))
                    if (a.overlaps(b)) problems.add(Problem.error(ZONE, ASSEMBLY, a.center().cell(),
                            "【" + ASSEMBLY.displayName() + "】和【" + other.displayName() + "】重叠了，站在重叠处会同时触发两边"));
        for (var landing : LANDINGS)
            for (var p : plan.points(landing))
                for (var avoid : KEEP_CLEAR)
                    if (nonEmpty(plan, avoid).stream().anyMatch(z -> z.contains(p.x(), p.y(), p.z(), false)))
                        problems.add(Problem.error(ZONE, landing, p.cell(),
                                "【" + landing.displayName() + "】放在了【" + avoid.displayName() + "】里，玩家一落地就会误触发"));
    }
    private static List<Zone> nonEmpty(LobbyPlan plan, LobbyFunction f) {
        return plan.zones(f).stream().filter(z -> !(z instanceof Zone.Floor floor && floor.isEmpty())).toList();
    }

    private static void bounds(LobbyPlan plan, int maxHorizontalSize, List<Problem> problems) {
        var bounds = plan.bounds();
        if (bounds.sizeX() > maxHorizontalSize || bounds.sizeZ() > maxHorizontalSize)
            problems.add(Problem.error(BOUNDS, null, null, "大厅范围 " + bounds.sizeX() + "×" + bounds.sizeZ()
                    + " 超过了水平上限 " + maxHorizontalSize + "×" + maxHorizontalSize));
        for (var entry : plan.points().entrySet())
            for (var p : entry.getValue()) if (!bounds.contains(p.x(), p.y(), p.z())) outside(problems, entry.getKey(), p.cell());
        for (var t : plan.triggers()) {
            var where = t.block() != null ? t.block() : t.anchor().cell();
            if (!bounds.contains(where)) outside(problems, t.function(), where);
        }
        for (var m : plan.models()) if (!bounds.contains(m.point().x(), m.point().y(), m.point().z())) outside(problems, m.function(), m.point().cell());
        for (var c : plan.altarLights()) if (!bounds.contains(c)) outside(problems, ALTAR_LIGHT, c);
        for (var t : plan.texts()) {
            var where = t.sign() != null ? t.sign() : t.anchor().cell();
            if (!bounds.contains(where)) outside(problems, t.function(), where);
        }
        for (var entry : plan.zones().entrySet())
            for (var zone : entry.getValue()) {
                var outside = switch (zone) {
                    case Zone.Floor f -> f.floor().stream().filter(c -> !bounds.contains(c)).findFirst();
                    case Zone.Volume v -> Optional.of(v.box().min()).filter(c -> !bounds.contains(c))
                            .or(() -> Optional.of(v.box().max()).filter(c -> !bounds.contains(c)));
                };
                outside.ifPresent(c -> outside(problems, entry.getKey(), c));
            }
    }
    private static void outside(List<Problem> problems, LobbyFunction f, Cell where) {
        problems.add(Problem.error(BOUNDS, f, where, "【" + f.displayName() + "】在大厅范围之外：" + where.x() + " " + where.y() + " " + where.z()));
    }
    private LobbyPlanValidator() {}
}
