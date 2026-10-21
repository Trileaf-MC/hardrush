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
}
