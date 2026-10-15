package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoundAdminTest {
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
        void millis(long millis) { now += millis * 1_000_000; }
    }
    private final Clock clock = new Clock();
    private RoundEngine create() {
        return RoundEngine.create(UUID.randomUUID(), 1, 123, RulesConfig.defaults().withMode(GameMode.NORMAL), clock);
    }
    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
    private RoundEngine countdown() {
        RoundEngine engine = create();
        accept(engine, engine.proposeWorldReady(UUID.randomUUID()));
        accept(engine, engine.proposeJoin(UUID.randomUUID(), A, "Alice"));
        accept(engine, engine.proposeJoin(UUID.randomUUID(), B, "Bob"));
        accept(engine, engine.proposeJoin(UUID.randomUUID(), C, "Charlie"));
        accept(engine, engine.proposeReady(UUID.randomUUID(), A, true));
        accept(engine, engine.proposeReady(UUID.randomUUID(), C, true));
        accept(engine, engine.proposeOnline(UUID.randomUUID(), C, "Charlie", false));
        return engine;
    }

    @Test void adminStartSkipsCountdownButOnlyAdmitsReadyOnlineLobbyPlayers() {
        RoundEngine engine = countdown();
        clock.millis(7_000);
        RoundTransition start = engine.proposeAdminStart(UUID.randomUUID());
        assertEquals("ADMIN_START", start.eventType());
        assertEquals(RoundPhase.COUNTDOWN, engine.view().phase());
        accept(engine, start);
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        assertEquals(0, engine.view().remainingPhaseNanos());
        assertEquals(ParticipantRole.HUMAN_ACTIVE, engine.view().participants().get(A).role());
        assertEquals(ParticipantRole.LOBBY, engine.view().participants().get(B).role());
        assertEquals(ParticipantRole.LOBBY, engine.view().participants().get(C).role());
        assertFalse(engine.view().participants().get(C).ready());
        assertTrue(engine.view().timingUncertain());
        clock.millis(4_000);
        assertEquals(4_000, engine.view().challengeMillis());
        accept(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        assertTrue(RoundEngine.restore(engine.committed(), clock).view().timingUncertain());
    }

    @Test void admissionAdminStartKeepsTimeStoppedUntilProfileUnlockIsDurable() {
        RoundEngine engine = countdown();
        accept(engine, engine.proposeAdmissionAdminStart(UUID.randomUUID()));
        assertEquals(ParticipantRole.HUMAN_ACTIVE, engine.view().participants().get(A).role());
        assertFalse(engine.view().participants().get(A).online());
        clock.millis(8_000);
        assertEquals(0, engine.view().challengeMillis());
        accept(engine, engine.proposeOnline(UUID.randomUUID(), A, "Alice", true));
        clock.millis(2_000);
        assertEquals(2_000, engine.view().challengeMillis());
    }

    @Test void noWorldNoReadyOrWrongPhaseCannotBeForcedIntoAStartedRound() {
        RoundEngine preparing = create();
        assertThrows(IllegalStateException.class, () -> preparing.proposeAdminStart(UUID.randomUUID()));
        accept(preparing, preparing.proposeWorldReady(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> preparing.proposeAdminStart(UUID.randomUUID()));
        accept(preparing, preparing.proposeJoin(UUID.randomUUID(), A, "Alice"));
        assertThrows(IllegalStateException.class, () -> preparing.proposeAdminStart(UUID.randomUUID()));
        accept(preparing, preparing.proposeReady(UUID.randomUUID(), A, true));
        accept(preparing, preparing.proposeOnline(UUID.randomUUID(), A, "Alice", false));
        assertThrows(IllegalStateException.class, () -> preparing.proposeAdminStart(UUID.randomUUID()));
        assertFalse(preparing.pending());
        RoundEngine running = countdown();
        accept(running, running.proposeAdminStart(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> running.proposeAdminStart(UUID.randomUUID()));
    }

    @Test void abortStopsAtItsCapturedBoundaryAndImmediatelyEntersSafeRotation() {
        RoundEngine engine = countdown();
        clock.millis(30_000);
        accept(engine, engine.proposeTick(UUID.randomUUID()));
        assertFalse(engine.view().timingUncertain());
        clock.millis(5_123);
        RoundTransition abort = engine.proposeAbort(UUID.randomUUID(), "活动提前结束");
        clock.millis(90_000);
        assertNull(engine.view().result());
        assertEquals(5_123, engine.view().challengeMillis());
        accept(engine, abort);
        assertEquals("ADMIN_ABORT", abort.eventType());
        assertEquals(RoundPhase.ROTATING, engine.view().phase());
        assertEquals(PauseReason.SETTLED, engine.view().pauseReason());
        assertEquals(RoundResult.Kind.ABORTED, engine.view().result().kind());
        assertEquals("活动提前结束", engine.view().result().reason());
        assertNull(engine.view().result().winner());
        assertEquals(5_123, engine.view().challengeMillis());
        assertEquals(5_123_000_000L, engine.view().participants().get(A).humanNanos());
        assertEquals(0, engine.view().remainingPhaseNanos());
        assertTrue(engine.view().timingUncertain());
        clock.millis(60_000);
        assertEquals(5_123, engine.view().challengeMillis());
    }

    @Test void preparingWaitingCountdownAndRescueCanBeAbortedWithoutInventingAWinner() {
        RoundEngine preparing = create();
        accept(preparing, preparing.proposeAbort(UUID.randomUUID(), "世界准备暂停"));
        assertEquals(RoundPhase.ROTATING, preparing.view().phase());
        assertTrue(preparing.view().participants().isEmpty());
        RoundEngine waiting = create();
        accept(waiting, waiting.proposeWorldReady(UUID.randomUUID()));
        accept(waiting, waiting.proposeAbort(UUID.randomUUID(), "未开赛取消"));
        RoundEngine countdown = countdown();
        accept(countdown, countdown.proposeAbort(UUID.randomUUID(), "倒计时取消"));
        assertEquals(0, countdown.view().challengeNanos());
        RoundSnapshot base = create().committed();
        RoundSnapshot rescue = new RoundSnapshot(base.roundId(), 1, 123, 0, base.rules(),
                RoundPhase.RESCUE, PauseReason.RESCUE, 10, 60_000_000_000L, Map.of(), true, false, null);
        RoundEngine resumed = RoundEngine.restore(rescue, clock);
        accept(resumed, resumed.proposeAbort(UUID.randomUUID(), "救场终止"));
        assertEquals(RoundPhase.ROTATING, resumed.view().phase());
        assertEquals(10, resumed.view().challengeNanos());
    }

    @Test void settledRoundAndRecoveryNeverAllowReplacingTheTerminalResult() {
        RoundEngine engine = countdown();
        clock.millis(30_000);
        accept(engine, engine.proposeTick(UUID.randomUUID()));
        accept(engine, engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.COMPLETED, A, "真实致死事件")));
        assertThrows(IllegalStateException.class, () -> engine.proposeAbort(UUID.randomUUID(), "不能覆盖通关"));
        accept(engine, engine.proposeTick(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> engine.proposeAbort(UUID.randomUUID(), "不能覆盖通关"));
        clock.millis(600_000);
        accept(engine, engine.proposeTick(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> engine.proposeAbort(UUID.randomUUID(), "不能覆盖通关"));
        assertEquals(A, engine.view().result().winner());
        RoundEngine recovery = RoundEngine.restore(create().committed(), clock);
        assertThrows(IllegalStateException.class, () -> recovery.proposeAbort(UUID.randomUUID(), "不能绕过恢复"));
    }

    @Test void unsafeAbortReasonsAndUncommittedManagementCannotChangeThePublishedState() {
        RoundEngine engine = countdown();
        assertThrows(IllegalArgumentException.class, () -> engine.proposeAbort(UUID.randomUUID(), "原因\n命令"));
        assertFalse(engine.pending());
        RoundTransition abort = engine.proposeAbort(UUID.randomUUID(), "正常原因");
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminStart(UUID.randomUUID()));
        assertTrue(engine.reject(abort, "database unavailable"));
        assertNull(engine.view().result());
        assertEquals(RoundPhase.RECOVERY_REQUIRED, engine.view().phase());
        assertFalse(engine.accept(abort));
    }
}
