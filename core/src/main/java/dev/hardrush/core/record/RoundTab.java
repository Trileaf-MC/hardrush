package dev.hardrush.core.record;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.book.SafeBookText;
import dev.hardrush.core.round.PauseReason;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import java.util.Locale;
import java.util.Objects;

/** Plain text for the vanilla TAB header/footer; never changes the online player list. */
public record RoundTab(String header, String footer) {
    public static RoundTab from(RoundSnapshot round, long endEntrants, String problem) {
        Objects.requireNonNull(round);
        if (endEntrants < 0) throw new IllegalArgumentException("Negative End entrant count");
        long offline = round.participants().values().stream().filter(p -> p.role().isActiveHuman() && !p.online()).count();
        long monsters = round.participants().values().stream().filter(p -> p.role().isMonster()).count();
        String state = phase(round);
        if (round.phase() == RoundPhase.RUNNING && round.pauseReason() != PauseReason.NONE) {
            state = "暂停（" + pause(round.pauseReason()) + "）";
        }
        String header = "HardRush · 第 " + round.sequence() + " 轮 · "
                + (round.rules().mode() == GameMode.NORMAL ? "普通" : "极限") + "\n" + state
                + " · 挑战用时 " + duration(round.challengeMillis());
        String footer = "人类在线 " + round.onlineHumans() + " / 离线 " + offline + " · 怪物 " + monsters
                + "\n进入过末地 " + endEntrants + " 人";
        long withdrawn = round.participants().values().stream().filter(p -> p.role() == dev.hardrush.core.round.ParticipantRole.WITHDRAWN).count();
        long disqualified = round.participants().values().stream().filter(p -> p.role() == dev.hardrush.core.round.ParticipantRole.DISQUALIFIED).count();
        if (withdrawn + disqualified > 0) footer += " · 主动退出 " + withdrawn + " / 取消资格 " + disqualified;
        if (round.result() != null) {
            String winner = round.result().winner() == null ? "待核验"
                    : safeName(round.participants().get(round.result().winner()).name());
            footer += " · " + switch (round.result().kind()) {
                case COMPLETED -> "尾刀：" + winner;
                case FAILED -> "挑战失败";
                case ABORTED -> "挑战中止";
            };
        }
        if (round.phase() == RoundPhase.COUNTDOWN) footer += "\n开局倒计时 " + seconds(round.remainingPhaseNanos()) + " 秒";
        if (round.phase() == RoundPhase.RESCUE) footer += "\n救场剩余 " + seconds(round.remainingPhaseNanos()) + " 秒";
        if (round.phase() == RoundPhase.SETTLING || round.phase() == RoundPhase.CELEBRATING) {
            footer += "\n轮换倒计时 " + seconds(round.remainingPhaseNanos()) + " 秒";
        }
        if (problem != null && !problem.isBlank()) footer += "\n状态保存／适配异常，请联系管理员";
        if (round.timingUncertain()) footer += "\n计时待核验";
        return new RoundTab(header, footer);
    }

    public static String duration(long millis) {
        if (millis < 0) throw new IllegalArgumentException("Negative duration");
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
    private static long seconds(long nanos) { return nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1); }
    private static String safeName(String name) {
        return SafeBookText.literal(name.replace("§", ""), 64);
    }
    private static String phase(RoundSnapshot round) {
        return switch (round.phase()) {
            case PREPARING -> "世界准备中";
            case WAITING -> "等待加入／就绪";
            case COUNTDOWN -> "开局倒计时";
            case RUNNING -> "进行中";
            case RESCUE -> "等待救场";
            case SETTLING -> RoundResultText.stageLabel(round.result(), "结算已保存");
            case CELEBRATING -> RoundResultText.stageLabel(round.result(), "通关庆祝");
            case ROTATING -> "正在轮换";
            case RECOVERY_REQUIRED -> "等待恢复核验";
        };
    }
    private static String pause(PauseReason reason) {
        return switch (reason) {
            case NONE -> "无";
            case NOT_RUNNING -> "尚未开局";
            case NO_ONLINE_HUMANS -> "无人类在线";
            case RESCUE -> "等待救场";
            case SETTLED -> "已结算";
            case RECOVERY_REQUIRED -> "等待恢复";
            case SERVER_STOPPED -> "停服";
        };
    }
}
