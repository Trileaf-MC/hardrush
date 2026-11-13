package dev.hardrush.core.record;

import dev.hardrush.core.book.SafeBookText;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Player wording of the history library (docs/plan/玩家消息文案.md, N2). Identifiers, reason codes, fingerprints and
 * milliseconds stay in {@code HistoryQueries}, which serves the operator commands.
 */
public final class LibraryText {
    private LibraryText() {}

    /** Elapsed time as players read it: {@code h:mm:ss}. */
    public static String clock(long millis) {
        if (millis < 0) throw new IllegalArgumentException("Negative duration");
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    public static String mode(GameMode mode) { return mode == GameMode.NORMAL ? "普通模式" : "极限挑战"; }

    public static String outcome(RoundResult result) {
        return switch (result.kind()) { case COMPLETED -> "挑战成功"; case FAILED -> "挑战失败"; case ABORTED -> "本轮已中止"; };
    }

    /** One round in the list: what, how it ended, how long, and whether the time still awaits review. */
    public static String round(RoundHistory row) {
        String line = "第 " + row.sequence() + " 轮 · " + mode(row.rules().mode()) + " · " + outcome(row.result());
        if (row.result().kind() == RoundResult.Kind.ABORTED) return line;
        return line + " · " + clock(row.challengeMillis())
                + (row.result().kind() == RoundResult.Kind.COMPLETED && !row.leaderboardEligible() ? "（有待核实）" : "");
    }

    public static List<String> details(RoundHistory row) {
        var lines = new ArrayList<String>();
        lines.add(round(row));
        lines.add(RoundResultText.reason(row.result()));
        if (row.result().kind() == RoundResult.Kind.COMPLETED)
            lines.add(row.leaderboardEligible() ? "成绩有效，计入排行榜" : "成绩有待管理员核实，暂不计入排行榜");
        lines.add("Minecraft " + row.rules().minecraftVersion() + " · " + difficulty(row.rules().difficulty()) + " · 种子 " + row.seed());
        return List.copyOf(lines);
    }

    public static String participant(ParticipantRecord p) {
        return SafeBookText.literal(p.playerName(), 64) + " · 人类 " + clock(p.humanNanos() / 1_000_000)
                + " · 阵亡 " + p.deathCount() + " · 复生 " + p.revivalCount() + " · 进度 " + p.advancementCount()
                + " · 末地 " + (p.firstEndNanos() == null ? "未到达" : clock(p.firstEndNanos() / 1_000_000))
                + " · 最终" + finalRole(p.finalRole());
    }

    public static String leader(LeaderboardEntry row) {
        return "第 " + row.rank() + " 名 · " + SafeBookText.literal(row.winnerName(), 64) + " · " + clock(row.round().challengeMillis())
                + " · 第 " + row.round().sequence() + " 轮";
    }

    private static String finalRole(ParticipantRole role) {
        return switch (role) {
            case HUMAN_ACTIVE, HUMAN_RESPAWNING -> "是人类";
            case MONSTER_LOBBY, MONSTER_DEPLOYED -> "是怪物";
            case WITHDRAWN -> "退出本轮";
            case DISQUALIFIED -> "被取消资格";
            case LOBBY, TRANSITION_PENDING -> "未参赛";
        };
    }

    private static String difficulty(dev.hardrush.core.config.Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "简单"; case NORMAL -> "普通"; case HARD -> "困难";
        };
    }
}
