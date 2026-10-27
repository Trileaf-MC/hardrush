package dev.hardrush.record;

import dev.hardrush.core.book.SafeBookText;
import dev.hardrush.core.record.*;
import dev.hardrush.round.RoundSession;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Shared bounded queries and literal presentation for commands and the native library menu. */
public final class HistoryQueries {
    public static final int PAGE_SIZE = 20;
    private final RoundSession session;
    public HistoryQueries(RoundSession session) { this.session = Objects.requireNonNull(session); }
    public CompletableFuture<List<RoundHistory>> recent(int page) { return session.history(PAGE_SIZE + 1, offset(page)); }
    public CompletableFuture<Optional<RoundHistory>> resolve(String input) {
        try {
            return input.matches("[1-9][0-9]{0,18}") ? session.historyBySequence(Long.parseLong(input)) : session.history(UUID.fromString(input));
        } catch (IllegalArgumentException error) { return CompletableFuture.failedFuture(error); }
    }
    public CompletableFuture<List<ParticipantRecord>> participants(UUID round, int page) { return session.participants(round, PAGE_SIZE + 1, offset(page)); }
    public CompletableFuture<List<LeaderboardEntry>> leaders(String fingerprint) { return session.leaderboard(fingerprint, 10); }
    private static int offset(int page) {
        if (page < 1 || page > 10000) throw new IllegalArgumentException("历史页码超出范围");
        return (page - 1) * PAGE_SIZE;
    }
    public static String line(RoundHistory row) {
        return "第 " + row.sequence() + " 轮 · " + (row.rules().mode() == dev.hardrush.core.config.GameMode.NORMAL ? "普通" : "极限")
                + " · " + switch(row.result().kind()) { case COMPLETED -> "通关"; case FAILED -> "失败"; case ABORTED -> "中止"; }
                + " · " + RoundTab.duration(row.challengeMillis()) + "（" + row.challengeMillis() + " ms）· "
                + (row.leaderboardEligible() ? "正常榜" : "保留／待核验");
    }
    public static List<String> details(RoundHistory row) {
        return List.of(line(row), "种子 " + row.seed() + " · 规则 " + row.rulesFingerprint(),
                "Minecraft " + row.rules().minecraftVersion() + " · " + row.rules().difficulty() + " · " + row.rules().generationRule(),
                "胜者UUID " + (row.result().winner() == null ? "无／待核验" : row.result().winner()),
                "原因 " + safe(row.result().reason()) + " · 计时待核验 " + row.timingUncertain() + " · 首龙归因已核验 " + row.trustedWinner(),
                "进度集合 " + (row.advancementSetFingerprints().isEmpty() ? "未采集" : String.join(",", row.advancementSetFingerprints().stream().sorted().toList())),
                "书版 " + row.edition() + " · 已发书册保留原快照");
    }
    public static String participant(ParticipantRecord p) {
        return safe(p.playerName()) + " · 人类 " + RoundTab.duration(p.humanMillis()) + " · 死亡 " + p.deathCount() + "／复生 "
                + p.revivalCount() + " · 进度 " + p.advancementCount() + " · 末地 "
                + (p.firstEndNanos() == null ? "未进入" : RoundTab.duration(p.firstEndNanos()/1_000_000)) + " · " + p.finalRole();
    }
    public static String leader(LeaderboardEntry row) { return "第 " + row.rank() + " 名 · " + safe(row.winnerName()) + " · "
            + row.round().challengeMillis() + " ms · 第 " + row.round().sequence() + " 轮"; }
    private static String safe(String text) { return SafeBookText.literal(text, 1024); }
}
