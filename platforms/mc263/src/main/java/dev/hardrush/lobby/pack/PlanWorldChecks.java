package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.world.RoundWorldBundle;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The acceptance rules that need the world (design 9): bound blocks have the right type, points can be stood on.
 * {@link MarkerScan} applies them while resolving markers; installing applies them to a pack's recorded plan after
 * placing it, since the markers are gone by then. Chunks must be loaded.
 */
public final class PlanWorldChecks {
    private PlanWorldChecks() {}

    /** Every block, sign and standing rule of {@code plan} against {@code level}. */
    public static List<Problem> check(ServerLevel level, LobbyPlan plan) {
        var problems = new ArrayList<Problem>();
        for (var trigger : plan.triggers()) {
            if (trigger.block() == null) continue; // Entity triggers are found by tag at runtime.
            var f = trigger.function();
            var state = level.getBlockState(pos(trigger.block()));
            var id = MarkerScan.blockId(state);
            if (!id.equals(trigger.blockId())) {
                problems.add(Problem.error(ProblemKind.BLOCK, f, trigger.block(), "【" + f.displayName() + "】绑定的方块应当是 "
                        + trigger.blockId() + "，这里是 " + id));
                continue;
            }
            requirement(f, trigger.block(), state).ifPresent(problems::add);
        }
        for (var light : plan.altarLights()) requirement(LobbyFunction.ALTAR_LIGHT, light, level.getBlockState(pos(light))).ifPresent(problems::add);
        for (var text : plan.texts()) {
            if (text.sign() != null) sign(text.function(), text.sign(), level.getBlockState(pos(text.sign()))).ifPresent(problems::add);
        }
        plan.points().forEach((f, points) -> {
            for (var p : points) standing(level, f, p, Severity.ERROR).ifPresent(problems::add);
        });
        for (var model : plan.models()) standing(level, model.function(), model.point(), Severity.WARNING).ifPresent(problems::add);
        return List.copyOf(problems);
    }

    /** A function with a block requirement (vaults, the altar light) bound to the wrong block or block state. */
    static Optional<Problem> requirement(LobbyFunction f, Cell cell, BlockState state) {
        var requirement = f.block().orElse(null);
        if (requirement == null) return Optional.empty();
        var id = MarkerScan.blockId(state);
        if (requirement.matches(id, MarkerScan.properties(state))) return Optional.empty();
        return Optional.of(Problem.error(ProblemKind.BLOCK, f, cell, "【" + f.displayName() + "】要绑定在 " + describe(requirement)
                + " 上，这里是 " + id));
    }

    /** Text functions write into signs; any sign kind will do. */
    static Optional<Problem> sign(LobbyFunction f, Cell cell, BlockState state) {
        if (state.getBlock() instanceof SignBlock) return Optional.empty();
        return Optional.of(Problem.error(ProblemKind.BLOCK, f, cell, "【" + f.displayName() + "】要绑定在告示牌上，这里是 " + MarkerScan.blockId(state)));
    }

    /** Solid ground under the feet and two free blocks for the body. */
    static Optional<Problem> standing(ServerLevel level, LobbyFunction f, Point at, Severity severity) {
        if (RoundWorldBundle.safeStanding(level, BlockPos.containing(at.x(), at.y(), at.z()))) return Optional.empty();
        var c = at.cell();
        var message = "【" + f.displayName() + "】（" + c.x() + " " + c.y() + " " + c.z() + "）站不住人：脚下要有实心方块，身体所在的两格要空着";
        return Optional.of(severity == Severity.ERROR ? Problem.error(ProblemKind.STANDING, f, c, message)
                : Problem.warning(ProblemKind.STANDING, f, c, message));
    }

    static String describe(BlockRequirement r) {
        if (r.properties().isEmpty()) return r.id();
        var joiner = new StringJoiner(",", r.id() + "[", "]");
        new TreeMap<>(r.properties()).forEach((k, v) -> joiner.add(k + "=" + v));
        return joiner.toString();
    }

    private static BlockPos pos(Cell c) { return new BlockPos(c.x(), c.y(), c.z()); }
}
