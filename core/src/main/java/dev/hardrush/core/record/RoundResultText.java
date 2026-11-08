package dev.hardrush.core.record;

import dev.hardrush.core.round.RoundResult;

/** Shared outcome wording; successful stages retain their surface-specific instructions. */
public final class RoundResultText {
    private RoundResultText() {}

    public static String stageLabel(RoundResult result, String completedLabel) {
        return switch (result.kind()) {
            case COMPLETED -> completedLabel;
            case FAILED -> "挑战失败";
            case ABORTED -> "挑战中止";
        };
    }

    /**
     * Player-facing explanation of a settled result. {@link RoundResult#reason()} holds either an engine code or
     * free text typed by an administrator (abort, recovery close, completed-result revision); neither is shown to
     * players verbatim, and an unknown failure code falls back to a generic sentence.
     * Administrators still read the raw reason through the audit and history detail commands.
     */
    public static String reason(RoundResult result) {
        return switch (result.kind()) {
            case FAILED -> switch (result.reason()) {
                case "ALL_HUMANS_DEAD" -> "所有人类都已阵亡";
                case "RESCUE_EXPIRED" -> "救场时间耗尽，没有人复生";
                case "HUMANS_OFFLINE" -> "人类全部离线，救场时间内没有人回来";
                case "NO_ELIGIBLE_PARTICIPANTS" -> "没有还能继续挑战的玩家";
                default -> "本轮挑战未能完成";
            };
            case ABORTED -> "管理员中止了本轮";
            case COMPLETED -> result.reason().equals("FIRST_DRAGON_HUMAN_FINAL_HIT") ? "胜者对末影龙打出了最后一击"
                    : result.winner() == null ? "末影龙已被击败，胜者有待管理员核实" : "末影龙已被击败";
        };
    }
}
