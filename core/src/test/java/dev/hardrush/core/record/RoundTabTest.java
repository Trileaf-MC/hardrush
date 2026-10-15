package dev.hardrush.core.record;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RoundTabTest {
    @Test void durationFloorsMillisAndSupportsRoundsLongerThanADay() {
        assertEquals("00:00:00", RoundTab.duration(999));
        assertEquals("01:02:03", RoundTab.duration(3_723_999));
        assertEquals("100:00:00", RoundTab.duration(360_000_000));
        assertThrows(IllegalArgumentException.class, () -> RoundTab.duration(-1));
    }
    @Test void offlineAndMonsterCountsFollowCommittedIdentity() {
        var online = human("Online", true);
        var offline = human("Offline", false);
        var id = UUID.randomUUID();
        var monster = new Participant(id, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 0, -1, 0, false);
        var round = snapshot(RoundPhase.RUNNING, PauseReason.NONE, 0,
                Map.of(online.id(), online, offline.id(), offline, id, monster), false, null);
        var text = RoundTab.from(round, 2, null);
        assertTrue(text.header().contains("极限"));
        assertTrue(text.footer().contains("人类在线 1 / 离线 1 · 怪物 1"));
        assertTrue(text.footer().contains("进入过末地 2 人"));
        assertThrows(IllegalArgumentException.class, () -> RoundTab.from(round, -1, null));
    }
    @Test void countdownsRoundUpAndPauseAndRecoveryProblemsArePlainStatus() {
        var countdown = RoundTab.from(snapshot(RoundPhase.COUNTDOWN, PauseReason.NOT_RUNNING, 2_001_000_000L,
                Map.of(), false, null), 0, null);
        assertTrue(countdown.footer().contains("开局倒计时 3 秒"));
        var paused = RoundTab.from(snapshot(RoundPhase.RUNNING, PauseReason.NO_ONLINE_HUMANS, 0,
                Map.of(), true, null), 0, "database-password-must-not-appear");
        assertTrue(paused.header().contains("暂停（无人类在线）"));
        assertTrue(paused.footer().contains("计时待核验"));
        assertTrue(paused.footer().contains("状态保存／适配异常"));
        assertFalse(paused.footer().contains("database-password"));
    }
    @Test void committedTerminalResultIncludesSanitizedWinnerAndFrozenCountdown() {
        var winner = human("甲§乙", true);
        var result = new RoundResult(RoundResult.Kind.COMPLETED, winner.id(), "trusted");
        var text = RoundTab.from(snapshot(RoundPhase.CELEBRATING, PauseReason.SETTLED, 600_000_000_000L,
                Map.of(winner.id(), winner), false, result), 5, null);
        assertTrue(text.footer().contains("尾刀：甲乙"));
        assertFalse(text.footer().contains("§"));
        assertTrue(text.footer().contains("轮换倒计时 600 秒"));
        var failed = RoundTab.from(snapshot(RoundPhase.SETTLING, PauseReason.SETTLED, 1,
                Map.of(), false, new RoundResult(RoundResult.Kind.FAILED, null, "no humans")), 0, null);
        assertTrue(failed.footer().contains("挑战失败"));
        assertTrue(failed.footer().contains("轮换倒计时 1 秒"));
    }
    @Test void rescueShowsRemainingTimeWithoutInventingOnlineHumansOrExposingEntitlementOwners() {
        var entitled = new Participant(UUID.randomUUID(), "SecretOwner", ParticipantRole.MONSTER_LOBBY,
                true, false, 12_000_000_000L, 0, 1, true);
        var outside = new Participant(UUID.randomUUID(), "Outside", ParticipantRole.MONSTER_DEPLOYED,
                false, false, 5_000_000_000L, 0, 1, false);
        var text = RoundTab.from(snapshot(RoundPhase.RESCUE, PauseReason.RESCUE, 59_000_000_001L,
                Map.of(entitled.id(), entitled, outside.id(), outside), false, null), 1, null);
        assertTrue(text.header().contains("等待救场"));
        assertTrue(text.footer().contains("救场剩余 60 秒"));
        assertTrue(text.footer().contains("人类在线 0 / 离线 0 · 怪物 2"));
        assertTrue(text.footer().contains("进入过末地 1 人"));
        assertFalse(text.footer().contains("SecretOwner"));
        assertFalse(text.footer().contains("尾刀"));
    }
    private static Participant human(String name, boolean online) {
        return new Participant(UUID.randomUUID(), name, ParticipantRole.HUMAN_ACTIVE, online, false, 0, 0, 1, false);
    }
    private static RoundSnapshot snapshot(RoundPhase phase, PauseReason pause, long remaining,
            Map<UUID, Participant> players, boolean uncertain, RoundResult result) {
        return new RoundSnapshot(UUID.randomUUID(), 1, 12345, 1, RulesConfig.defaults().withMode(GameMode.HARDCORE),
                phase, pause, 3_723_999_000_000L, remaining, players, false, uncertain, result);
    }
}
