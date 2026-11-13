package dev.hardrush.core.record;

import dev.hardrush.core.book.SafeBookText;
import dev.hardrush.core.round.PauseReason;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import java.util.Objects;

/** Plain text for the vanilla TAB header/footer; never changes the online player list. */
public record RoundTab(String header, String footer) {
    public static RoundTab from(RoundSnapshot round, long endEntrants, String problem) {
        Objects.requireNonNull(round);
        if (endEntrants < 0) throw new IllegalArgumentException("Negative End entrant count");
        long offline = round.participants().values().stream().filter(p -> p.role().isActiveHuman() && !p.online()).count();
        long monsters = round.participants().values().stream().filter(p -> p.role().isMonster() && p.online()).count();
        long offlineMonsters = round.participants().values().stream().filter(p -> p.role().isMonster() && !p.online()).count();
        // The status bar shows state and progress; TAB is the scoreboard players hold open for the exact numbers.
        String state = phase(round);
        if (round.phase() == RoundPhase.RUNNING && round.pauseReason() == PauseReason.SERVER_STOPPED) state = "计时暂停 · 服务器重启";
        else if (round.phase() == RoundPhase.RUNNING && round.pauseReason() != PauseReason.NONE) state = "计时暂停 · 等待人类上线";
        if (round.phase() == RoundPhase.COUNTDOWN || round.phase() == RoundPhase.RESCUE
                || round.phase() == RoundPhase.SETTLING || round.phase() == RoundPhase.CELEBRATING) {
            state += " · 剩余 " + seconds(round.remainingPhaseNanos()) + " 秒";
        }
        String header = "HardRush · 第 " + round.sequence() + " 轮 · " + LibraryText.mode(round.rules().mode())
                + "\n" + state + " · 用时 " + duration(round.challengeMillis());
        String footer = "人类 " + round.onlineHumans() + (offline > 0 ? "（离线 " + offline + "）· " : " · ")
                + "怪物 " + monsters + (offlineMonsters > 0 ? "（离线 " + offlineMonsters + "）" : "")
                + "\n进入过末地 " + endEntrants + " 人";
        long withdrawn = round.participants().values().stream().filter(p -> p.role() == dev.hardrush.core.round.ParticipantRole.WITHDRAWN).count();
        long disqualified = round.participants().values().stream().filter(p -> p.role() == dev.hardrush.core.round.ParticipantRole.DISQUALIFIED).count();
        if (withdrawn > 0) footer += " · 退出 " + withdrawn;
        if (disqualified > 0) footer += " · 取消资格 " + disqualified;
        // Failure and abort are already the stage on the header; only a success names its winner.
        if (round.result() != null && round.result().kind() == dev.hardrush.core.round.RoundResult.Kind.COMPLETED) {
            footer += round.result().winner() == null ? " · 胜者有待核实"
                    : " · 胜者 " + safeName(round.participants().get(round.result().winner()).name());
        }
        if (problem != null && !problem.isBlank()) footer += "\n比赛出了点问题，请联系管理员";
        if (round.timingUncertain()) footer += "\n用时有待管理员核实";
        return new RoundTab(header, footer);
    }

    /** Elapsed time as players read it, {@code h:mm:ss}; see {@link LibraryText#clock}. */
    public static String duration(long millis) { return LibraryText.clock(millis); }
    private static long seconds(long nanos) { return nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1); }
    private static String safeName(String name) {
        return SafeBookText.literal(name.replace("§", ""), 64);
    }
    private static String phase(RoundSnapshot round) {
        return switch (round.phase()) {
            case PREPARING -> "世界准备中";
            case WAITING -> "等待开局";
            case COUNTDOWN -> "开局倒计时";
            case RUNNING -> "进行中";
            case RESCUE -> round.awaitingReconnect() ? "人类全部离线（等待重连）" : "救场中";
            case SETTLING -> RoundResultText.stageLabel(round.result(), "挑战成功");
            case CELEBRATING -> RoundResultText.stageLabel(round.result(), "通关庆祝");
            case ROTATING -> "正在换轮";
            case RECOVERY_REQUIRED -> "比赛已暂停";
        };
    }
}
