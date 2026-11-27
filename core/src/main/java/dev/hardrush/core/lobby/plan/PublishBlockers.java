package dev.hardrush.core.lobby.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * What the workshop tells a builder about the problems that block publishing (design 9): a short label per problem
 * for the status bar, and what to hand them so they can fix the missing ones (marker items, and the block a function
 * must be bound to).
 */
public final class PublishBlockers {
    /** Bindings a missing function's marker is handed out as, most direct first: a click marker needs a block too. */
    private static final List<Binding> PREFERRED = List.of(Binding.POINT, Binding.ZONE, Binding.MODEL, Binding.BLOCK, Binding.TEXT, Binding.CLICK);

    private PublishBlockers() {}

    /**
     * What to hand out for one function. {@code markers}: how many marker items of {@code binding} are still missing.
     * {@code block}: the function needs a particular block ({@link LobbyFunction#block()}, or a sign for text) that the
     * draft lacks, so one of those is handed out too.
     */
    public record Need(LobbyFunction function, Binding binding, int markers, boolean block) {}

    /** A few words naming the problem, for a status bar. */
    public static String label(Problem p) {
        var f = p.function() == null ? "" : "【" + p.function().displayName() + "】";
        return switch (p.kind()) {
            case MISSING -> "缺" + f;
            case COUNT -> f + "数量不对";
            case MARKER -> "标记有误";
            case BLOCK -> f + "方块不对";
            case STANDING -> f + "站不住人";
            case ZONE -> f + "区域有误";
            case BOUNDS -> f.isEmpty() ? "范围有误" : f + "超出范围";
            case SANITIZED -> f + "内容会被清洗";
            case CAPACITY -> f + "数量偏少";
        };
    }

    /** The first {@code shown} distinct labels, and how many problems there are in all when that is more. */
    public static String summary(List<Problem> blocking, int shown) {
        var labels = new LinkedHashSet<String>();
        for (var p : blocking) labels.add(label(p));
        var named = labels.stream().limit(shown).toList();
        var text = String.join("、", named);
        return labels.size() > named.size() ? text + " 等 " + blocking.size() + " 项" : text;
    }

    /**
     * The chat list of {@code blocking}: every missing function folded into one line (an empty draft misses most of
     * them, and they all read alike), then the other problems one per line, at most {@code shown} lines in all.
     */
    public static List<String> chatLines(List<Problem> blocking, int shown) {
        var lines = new ArrayList<String>();
        var missing = new LinkedHashSet<String>();
        var others = new ArrayList<Problem>();
        for (var p : blocking) {
            if (p.kind() == ProblemKind.MISSING && p.function() != null) missing.add(p.function().displayName());
            else others.add(p);
        }
        if (!missing.isEmpty()) lines.add("缺 " + missing.size() + " 个功能：" + String.join("、", missing));
        int room = Math.max(0, shown - lines.size());
        int listed = Math.min(room, others.size());
        for (int i = 0; i < listed; i++) lines.add(others.get(i).message());
        if (others.size() > listed) lines.add("…还有 " + (others.size() - listed) + " 项，工具箱的验收清单里能逐条传送过去");
        return lines;
    }

    /**
     * The tools that fix {@code blocking}, one entry per function in problem order. {@code bound} counts the bindings of
     * a function the check saw. Problems no item fixes (overlaps, bad tags, standing room) need none.
     */
    public static List<Need> needs(List<Problem> blocking, ToIntFunction<LobbyFunction> bound) {
        var out = new LinkedHashMap<LobbyFunction, Need>();
        for (var p : blocking) {
            var f = p.function();
            if (f == null || p.severity() != Severity.ERROR) continue;
            var binding = preferred(f);
            boolean needsBlock = f.block().isPresent() || binding == Binding.TEXT;
            var need = switch (p.kind()) {
                case MISSING, COUNT -> {
                    int missing = f.min() - bound.applyAsInt(f);
                    yield missing <= 0 ? null : new Need(f, binding, missing, needsBlock);
                }
                case BLOCK -> needsBlock ? new Need(f, binding, 0, true) : null;
                default -> null;
            };
            if (need == null) continue;
            out.merge(f, need, (a, b) -> new Need(f, binding, Math.max(a.markers(), b.markers()), a.block() || b.block()));
        }
        return List.copyOf(new ArrayList<>(out.values()));
    }

    /** The binding a missing function's marker is handed out as. */
    public static Binding preferred(LobbyFunction f) {
        for (var b : PREFERRED) if (f.bindings().contains(b)) return b;
        throw new IllegalStateException("No binding for " + f);
    }
}
