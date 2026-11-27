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
 * loaded. Builders see the outcome on their status bar, which names what blocks publishing, and on their
 * {@link Checklist} item; markers with problems change colour ({@link MarkerView}).
 *
 * <p>Only loaded chunks: a 256×256 draft is never loaded whole while building, and the light check must not load it.
 * The chunks around builders are loaded anyway, so what they are working on is always checked; a function bound only in
 * a far corner can read as missing until somebody goes there. Publishing scans everything.
 */
final class WorkshopChecks {
    static final int PERIOD = 40;
    /** How many blocking problems the status bar names before "and N more". */
    static final int SHOWN_IN_BAR = 3;

    /**
     * {@code flagged}: cells problems point at, for colouring markers. {@code counts}: how many of each function the
     * loaded part of the draft binds, so the checklist knows how many markers are still missing.
     */
    record Result(List<Problem> problems, Set<Cell> flagged, Map<LobbyFunction, Integer> counts) {
        Result { problems = List.copyOf(problems); flagged = Set.copyOf(flagged); counts = Map.copyOf(counts); }
        long errors() { return problems.stream().filter(p -> p.severity() == Severity.ERROR).count(); }
        long warnings() { return problems.size() - errors(); }
        List<Problem> blocking() { return problems.stream().filter(p -> p.severity() == Severity.ERROR).toList(); }
        int count(LobbyFunction f) { return counts.getOrDefault(f, 0); }
    }

    private final Map<String, Result> results = new HashMap<>();
    private final Map<String, ServerBossEvent> bars = new HashMap<>();

    /** The checklist item for each checked draft, rebuilt only when a check reads differently. */
    private final Map<String, net.minecraft.world.item.ItemStack> checklists = new HashMap<>();

    Optional<Result> result(String draftId) { return Optional.ofNullable(results.get(draftId)); }

    /** The checklist builders of {@code draftId} carry now; do not modify it. */
    net.minecraft.world.item.ItemStack checklist(String draftId) {
        return checklists.computeIfAbsent(draftId, id -> Checklist.item(results.get(id)));
    }

    void forget(net.minecraft.server.MinecraftServer server, String draftId) {
        results.remove(draftId);
        checklists.remove(draftId);
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
        var counts = new java.util.EnumMap<LobbyFunction, Integer>(LobbyFunction.class);
        for (var f : LobbyFunction.values()) counts.put(f, scan.plan().count(f));
        var result = new Result(scan.problems(), flagged, counts);
        var before = results.put(draft.id(), result);
        if (before == null || !before.problems().equals(result.problems())) checklists.remove(draft.id());
        // Turning publishable is worth a word to everybody building it; the bar alone turns green quietly.
        if (before != null && before.errors() > 0 && result.errors() == 0)
            for (var builder : workshop.builders(draft.id())) dev.hardrush.notice.Notices.hint(builder, "「" + draft.name() + "」的阻断项都处理好了 · 可以发布");
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
        String text = errors > 0 ? "「" + draft.name() + "」不能发布 · " + dev.hardrush.core.lobby.plan.PublishBlockers.summary(result.blocking(), SHOWN_IN_BAR) + " · 右键验收清单"
                : "「" + draft.name() + "」可以发布" + (warnings > 0 ? " · " + warnings + " 条提醒" : "");
        bar.setName(Component.literal(text));
        bar.setColor(errors > 0 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.GREEN);
        bar.setProgress(1);
        return bar;
    }
}
