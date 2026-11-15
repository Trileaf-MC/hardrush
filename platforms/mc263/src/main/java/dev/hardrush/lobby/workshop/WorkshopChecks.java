package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.Problem;
import dev.hardrush.core.lobby.plan.Severity;
import dev.hardrush.lobby.pack.MarkerScan;
import dev.hardrush.notice.StatusBars;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.BossEvent;

/**
 * The workshop's light acceptance check (design 9, last paragraph): every {@link #PERIOD} ticks, each draft somebody
 * builds in is checked with {@link MarkerScan#preview}, the same rules as publishing over the markers whose chunks are
 * loaded. Builders see the outcome on their status bar and in the toolbox checklist; markers with problems change
 * colour ({@link MarkerView}).
 *
 * <p>Only loaded chunks: a 256×256 draft is never loaded whole while building, and the light check must not load it.
 * The chunks around builders are loaded anyway, so what they are working on is always checked; a function bound only in
 * a far corner can read as missing until somebody goes there. Publishing scans everything.
 */
final class WorkshopChecks {
    static final int PERIOD = 40;

    /** {@code flagged}: cells problems point at, for colouring markers. */
    record Result(List<Problem> problems, Set<Cell> flagged) {
        Result { problems = List.copyOf(problems); flagged = Set.copyOf(flagged); }
        long errors() { return problems.stream().filter(p -> p.severity() == Severity.ERROR).count(); }
        long warnings() { return problems.size() - errors(); }
    }

    private final Map<String, Result> results = new HashMap<>();
    private final Map<String, ServerBossEvent> bars = new HashMap<>();

    Optional<Result> result(String draftId) { return Optional.ofNullable(results.get(draftId)); }

    void forget(net.minecraft.server.MinecraftServer server, String draftId) {
        results.remove(draftId);
        var bar = bars.remove(draftId);
        if (bar != null) StatusBars.hideEverywhere(server, bar);
    }

    /** Re-checks drafts with builders on schedule and keeps every builder's bar on their own draft. */
    void tick(Workshop workshop, int tickCount) {
        var server = workshop.server();
        if (tickCount % PERIOD == 0) {
            for (var draft : workshop.drafts()) {
                if (workshop.builders(draft.id()).isEmpty()) continue;
                var level = workshop.level(draft.id()).orElse(null);
                if (level != null) check(workshop, draft, level);
            }
        }
        for (var player : server.getPlayerList().getPlayers()) {
            var draft = workshop.buildingIn(player).orElse(null);
            var bar = draft == null ? null : bar(draft);
            if (bar == null) StatusBars.hide(player, StatusBars.Slot.WORKSHOP);
            else StatusBars.show(player, StatusBars.Slot.WORKSHOP, bar);
        }
    }

    /** Checks one draft now; tests and the toolbox call it outside the schedule. */
    Result check(Workshop workshop, Draft draft, ServerLevel level) {
        var area = draft.range(level.getMinY(), level.getMinY() + level.getHeight() - 1);
        var scan = MarkerScan.preview(level, area, dev.hardrush.HardRushProperties.lobbyMaxSize());
        var flagged = new HashSet<Cell>();
        for (var p : scan.problems()) if (p.where() != null) flagged.add(p.where());
        var result = new Result(scan.problems(), flagged);
        results.put(draft.id(), result);
        // Builders arrive at the first central landing they placed (design 11.2).
        var central = scan.plan().points(LobbyFunction.LANDING_CENTRAL);
        if (!central.isEmpty() && !central.getFirst().equals(draft.entry())) workshop.updateEntry(draft.id(), central.getFirst());
        return result;
    }

    private ServerBossEvent bar(Draft draft) {
        var result = results.get(draft.id());
        if (result == null) return null;
        var bar = bars.computeIfAbsent(draft.id(), id -> new ServerBossEvent(UUID.randomUUID(), Component.empty(),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS));
        long errors = result.errors(), warnings = result.warnings();
        String text = errors > 0 ? "「" + draft.name() + "」还缺 " + errors + " 项 · 工具箱里看验收清单"
                : "「" + draft.name() + "」可以发布" + (warnings > 0 ? " · " + warnings + " 条提醒" : "");
        bar.setName(Component.literal(text));
        bar.setColor(errors > 0 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.GREEN);
        bar.setProgress(1);
        return bar;
    }
}
