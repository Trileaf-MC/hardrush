package dev.hardrush.core.record;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.round.RoundResult;
import dev.hardrush.core.round.RoundResult.Kind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RoundResultTextTest {
    @Test void engineFailureCodesAreTranslated() {
        assertEquals("所有人类都已阵亡", RoundResultText.reason(new RoundResult(Kind.FAILED, null, "ALL_HUMANS_DEAD")));
        assertEquals("救场时间耗尽，没有人复生", RoundResultText.reason(new RoundResult(Kind.FAILED, null, "RESCUE_EXPIRED")));
        assertEquals("没有还能继续挑战的玩家", RoundResultText.reason(new RoundResult(Kind.FAILED, null, "NO_ELIGIBLE_PARTICIPANTS")));
        assertEquals("本轮挑战未能完成", RoundResultText.reason(new RoundResult(Kind.FAILED, null, "SOME_FUTURE_CODE")));
    }

    @Test void administratorTextIsNeverShownToPlayers() {
        assertEquals("管理员中止了本轮", RoundResultText.reason(new RoundResult(Kind.ABORTED, null, "某玩家开挂，先停一下")));
        assertEquals("末影龙已被击败", RoundResultText.reason(new RoundResult(Kind.COMPLETED, UUID.randomUUID(), "人工改判")));
    }

    @Test void completionExplainsWhetherTheWinnerIsVerified() {
        assertEquals("胜者对末影龙打出了最后一击",
                RoundResultText.reason(new RoundResult(Kind.COMPLETED, UUID.randomUUID(), "FIRST_DRAGON_HUMAN_FINAL_HIT")));
        assertEquals("末影龙已被击败，胜者有待管理员核实", RoundResultText.reason(
                new RoundResult(Kind.COMPLETED, null, "DRAGON_DEATH_PENDING_VERIFICATION:MONSTER_SOURCE")));
    }
}
