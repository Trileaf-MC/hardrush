package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoundEngineTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
        void millis(long value) { now += value * 1_000_000; }
    }
    private final Clock clock = new Clock();
    private RoundEngine create() { return RoundEngine.create(UUID.randomUUID(), 1, 123, RulesConfig.defaults().withMode(GameMode.NORMAL), clock); }
    private static void apply(RoundEngine engine, RoundTransition event) { assertTrue(engine.accept(event)); }
    private RoundEngine waiting() {
        RoundEngine engine = create();
        apply(engine, engine.proposeWorldReady(UUID.randomUUID()));
        return engine;
    }
    private RoundEngine running() {
        RoundEngine engine = waiting();
        apply(engine, engine.proposeJoin(UUID.randomUUID(), A, "Alice"));
        apply(engine, engine.proposeReady(UUID.randomUUID(), A, true));
        clock.millis(30_000);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        return engine;
    }

    @Test void incompleteWorldNeverAcceptsJoinOrReady() {
        RoundEngine engine = create();
        assertThrows(IllegalStateException.class, () -> engine.proposeJoin(UUID.randomUUID(), A, "Alice"));
        assertThrows(IllegalStateException.class, () -> engine.proposeReady(UUID.randomUUID(), A, true));
        assertFalse(engine.pending());
        assertTrue(engine.view().participants().isEmpty());
    }

    @Test void countdownCancellationRestartsThirtySecondsAndStartsOnlyReadyOnlinePlayers() {
        RoundEngine engine = waiting();
        apply(engine, engine.proposeJoin(UUID.randomUUID(), A, "Alice"));
        apply(engine, engine.proposeJoin(UUID.randomUUID(), B, "Bob"));
        apply(engine, engine.proposeReady(UUID.randomUUID(), A, true));
        clock.millis(15_000);
        assertEquals(15_000, engine.view().remainingPhaseMillis());
        apply(engine, engine.proposeReady(UUID.randomUUID(), A, false));
        assertEquals(RoundPhase.WAITING, engine.view().phase());
        apply(engine, engine.proposeReady(UUID.randomUUID(), A, true));
        assertEquals(30_000, engine.view().remainingPhaseMillis());
        clock.millis(30_000);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(ParticipantRole.HUMAN_ACTIVE, engine.view().participants().get(A).role());
        assertEquals(ParticipantRole.LOBBY, engine.view().participants().get(B).role());
    }

    @Test void finalReadyDisconnectCancelsCountdownAndRenameDoesNotCreateAnotherParticipant() {
        RoundEngine engine = waiting();
        apply(engine, engine.proposeJoin(UUID.randomUUID(), A, "Alice"));
        apply(engine, engine.proposeReady(UUID.randomUUID(), A, true));
        apply(engine, engine.proposeOnline(UUID.randomUUID(), A, "Alice2", false));
        assertEquals(RoundPhase.WAITING, engine.view().phase());
        apply(engine, engine.proposeOnline(UUID.randomUUID(), A, "Alice3", true));
        assertEquals(1, engine.view().participants().size());
        assertEquals("Alice3", engine.view().participants().get(A).name());
        assertFalse(engine.view().participants().get(A).ready());
    }

    @Test void lagAndSparseTicksUseActualElapsedTimeAndPreserveNanosecondRemainder() {
        RoundEngine engine = running();
        clock.millis(5_200);
        clock.now += 731;
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(5_200_000_731L, engine.view().challengeNanos());
        assertEquals(5_200_000_731L, engine.view().participants().get(A).humanNanos());
        clock.millis(1_800);
        assertEquals(7_000_000_731L, engine.view().challengeNanos());
    }

    @Test void lateJoiningHumanHasSeparatePersonalTimeAndRoundElapsedJoinOffset() {
        RoundEngine engine = running();
        clock.millis(12_000);
        apply(engine, engine.proposeJoin(UUID.randomUUID(), B, "Bob"));
        clock.millis(3_000);
        RoundSnapshot current = engine.view();
        assertEquals(15_000, current.challengeMillis());
        assertEquals(12_000_000_000L, current.participants().get(B).joinedAtChallengeNanos());
        assertEquals(3_000_000_000L, current.participants().get(B).humanNanos());
        assertEquals(15_000_000_000L, current.participants().get(A).humanNanos());
    }

    @Test void allHumansOfflinePausesWhileKeepingTheirMembershipAndPositionContract() {
        RoundEngine engine = running();
        clock.millis(5_000);
        apply(engine, engine.proposeOnline(UUID.randomUUID(), A, "Alice", false));
        clock.millis(90_000);
        assertEquals(5_000, engine.view().challengeMillis());
        assertEquals(PauseReason.NO_ONLINE_HUMANS, engine.view().pauseReason());
        assertEquals(ParticipantRole.HUMAN_ACTIVE, engine.view().participants().get(A).role());
        apply(engine, engine.proposeOnline(UUID.randomUUID(), A, "Alice", true));
        clock.millis(2_000);
        assertEquals(7_000, engine.view().challengeMillis());
    }

    @Test void normalDeathScreenPausesOnlyDeadHumansWithoutEndingTheRound() {
        RoundEngine engine = running();
        clock.millis(2_000);
        apply(engine, engine.proposeNormalDeath(UUID.randomUUID(), A, 1));
        clock.millis(30_000);
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        assertEquals(2_000, engine.view().challengeMillis());
        assertThrows(IllegalStateException.class, () -> engine.proposeJoin(UUID.randomUUID(), A, "Alice"));
        apply(engine, engine.proposeNormalRespawn(UUID.randomUUID(), A, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeNormalDeath(UUID.randomUUID(), A, 1));
        clock.millis(3_000);
        assertEquals(5_000, engine.view().challengeMillis());
        assertEquals(2, engine.view().participants().get(A).lifeSequence());
    }

    @Test void oneDeadHumanDoesNotPauseOtherLiveHumans() {
        RoundEngine engine = running();
        apply(engine, engine.proposeJoin(UUID.randomUUID(), B, "Bob"));
        apply(engine, engine.proposeNormalDeath(UUID.randomUUID(), A, 1));
        clock.millis(5_000);
        assertEquals(5_000, engine.view().challengeMillis());
        assertEquals(0, engine.view().participants().get(A).humanNanos());
        assertEquals(5_000_000_000L, engine.view().participants().get(B).humanNanos());
    }

    @Test void writeFailureNeverPublishesProposedParticipantAndHaltsConflictActions() {
        RoundEngine engine = waiting();
        RoundTransition join = engine.proposeJoin(UUID.randomUUID(), A, "Alice");
        assertTrue(engine.pending());
        assertTrue(engine.view().participants().isEmpty());
        assertThrows(IllegalStateException.class, () -> engine.proposeJoin(UUID.randomUUID(), B, "Bob"));
        assertTrue(engine.reject(join, "disk full"));
        assertEquals(RoundPhase.RECOVERY_REQUIRED, engine.view().phase());
        assertTrue(engine.view().participants().isEmpty());
        assertFalse(engine.accept(join));
    }

    @Test void oldRoundOrDuplicateCallbacksCannotCommitCurrentPendingMutation() {
        RoundEngine first = waiting();
        RoundTransition old = first.proposeJoin(UUID.randomUUID(), A, "Alice");
        RoundEngine second = waiting();
        RoundTransition current = second.proposeJoin(UUID.randomUUID(), B, "Bob");
        assertFalse(second.accept(old));
        assertTrue(second.pending());
        assertTrue(second.accept(current));
        assertFalse(second.accept(current));
        assertFalse(second.reject(old, "old failure"));
        assertEquals(ParticipantRole.LOBBY, second.view().participants().get(B).role());
    }

    @Test void cleanStopRestoresCheckpointWithoutCountingDowntimeOrStaleOnlineSessions() {
        RoundEngine engine = running();
        clock.millis(12_345);
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        RoundSnapshot saved = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed()));
        clock.millis(8_000_000);
        RoundEngine restored = RoundEngine.restore(saved, clock);
        assertEquals(saved.roundId(), restored.view().roundId());
        assertEquals(12_345, restored.view().challengeMillis());
        assertFalse(restored.view().participants().get(A).online());
        apply(restored, restored.proposeOnline(UUID.randomUUID(), A, "Alice", true));
        clock.millis(2_000);
        assertEquals(14_345, restored.view().challengeMillis());
        assertFalse(restored.view().timingUncertain());
    }

    @Test void dirtyStopKeepsDurableTotalsButFlagsRecoveryRatherThanInventingMissingTime() {
        RoundEngine engine = running();
        clock.millis(9_000);
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), false));
        clock.millis(6_000);
        RoundEngine restored = RoundEngine.restore(engine.committed(), clock);
        assertEquals(9_000, restored.view().challengeMillis());
        assertEquals(RoundPhase.RECOVERY_REQUIRED, restored.view().phase());
        assertTrue(restored.view().timingUncertain());
    }

    @Test void stopKeepsCountdownRemainderAndDoesNotCountServerDowntime() {
        RoundEngine engine = waiting();
        apply(engine, engine.proposeJoin(UUID.randomUUID(), A, "Alice"));
        apply(engine, engine.proposeReady(UUID.randomUUID(), A, true));
        clock.millis(7_000);
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        clock.millis(3_000_000);
        RoundEngine restored = RoundEngine.restore(engine.committed(), clock);
        assertEquals(23_000, restored.view().remainingPhaseMillis());
        apply(restored, restored.proposeOnline(UUID.randomUUID(), A, "Alice", true));
        clock.millis(23_000);
        apply(restored, restored.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, restored.view().phase());
        assertEquals(0, restored.view().challengeMillis());
    }

    @Test void terminalHookCapturesTimeBeforeDatabaseLatencyAndKeepsCelebrationOrigin() {
        RoundEngine engine = running();
        clock.millis(8_765);
        RoundTransition finish = engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.COMPLETED, A, "verified future dragon event"));
        clock.millis(12_000);
        assertNull(engine.view().result());
        assertEquals(8_765, engine.view().challengeMillis());
        apply(engine, finish);
        assertEquals(8_765, engine.view().challengeMillis());
        assertEquals(168_000, engine.view().remainingPhaseMillis());
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.CELEBRATING, engine.view().phase());
        assertThrows(IllegalStateException.class, () -> engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.COMPLETED, A, "duplicate")));
        clock.millis(168_000);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.ROTATING, engine.view().phase());
    }

    @Test void allReturnedEventPreservesResultAndFrozenTimeAndCannotBeReplayed() {
        RoundEngine engine = running();
        clock.millis(12_345);
        var result = new RoundResult(RoundResult.Kind.COMPLETED, A, "verified hook");
        apply(engine, engine.proposeFinish(UUID.randomUUID(), result));
        clock.millis(2_000);
        apply(engine, engine.proposeCelebrationComplete(UUID.randomUUID()));
        assertEquals(RoundPhase.ROTATING, engine.view().phase());
        assertEquals(0, engine.view().remainingPhaseMillis());
        assertEquals(12_345, engine.view().challengeMillis());
        assertEquals(result, engine.view().result());
        assertThrows(IllegalStateException.class, () -> engine.proposeCelebrationComplete(UUID.randomUUID()));
    }

    @Test void celebrationCompleteCannotFinishAnActiveOrFailedRound() {
        RoundEngine engine = running();
        assertThrows(IllegalStateException.class, () -> engine.proposeCelebrationComplete(UUID.randomUUID()));
        apply(engine, engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.FAILED, null, "failed")));
        assertThrows(IllegalStateException.class, () -> engine.proposeCelebrationComplete(UUID.randomUUID()));
        assertEquals(30_000, engine.view().remainingPhaseMillis());
    }

    @Test void legacyFailedCelebrationRestoresAsFailureDisplayWithoutResettingItsTimer() {
        var failed = new RoundResult(RoundResult.Kind.FAILED, null, "ALL_HUMANS_DEAD");
        var stored = new RoundSnapshot(UUID.randomUUID(), 1, 123, 7, RulesConfig.defaults(),
                RoundPhase.CELEBRATING, PauseReason.SERVER_STOPPED, 12_345_000_000L, 17_000_000_000L,
                Map.of(), true, false, failed);
        var engine = RoundEngine.restore(stored, clock);
        clock.millis(100_000); // Time spent offline does not consume the saved display remainder.
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
        assertEquals(17_000, engine.view().remainingPhaseMillis());
        clock.millis(16_999);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
        assertEquals(1, engine.view().remainingPhaseMillis());
        clock.millis(1);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.ROTATING, engine.view().phase());
        assertEquals(12_345, engine.view().challengeMillis());
        assertEquals(failed, engine.view().result());
    }

    @Test void oldPendingTenMinuteRulesAreCappedWithoutChangingTheirFingerprint() {
        var defaults = RulesConfig.defaults();
        var legacy = RulesConfig.decode(defaults.encode().replace("successCelebrationMillis=180000", "successCelebrationMillis=600000"));
        RoundEngine engine = RoundEngine.create(UUID.randomUUID(), 1, 1, legacy, clock);
        apply(engine, engine.proposeWorldReady(UUID.randomUUID()));
        apply(engine, engine.proposeJoin(UUID.randomUUID(), A, "Alice"));
        apply(engine, engine.proposeReady(UUID.randomUUID(), A, true));
        clock.millis(30_000);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        apply(engine, engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.COMPLETED, A, "verified hook")));
        assertEquals(180_000, engine.view().remainingPhaseMillis());
        assertEquals(legacy.fingerprint(), engine.view().rules().fingerprint());
        clock.millis(179_999);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.CELEBRATING, engine.view().phase());
        clock.millis(1);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.ROTATING, engine.view().phase());
    }

    @Test void codecIsDeterministicRejectsTruncationAndDoesNotAliasMutablePlayerMap() {
        RoundEngine engine = running();
        apply(engine, engine.proposeJoin(UUID.randomUUID(), B, "Bob"));
        RoundSnapshot snapshot = engine.committed();
        var reversed = new LinkedHashMap<UUID, Participant>();
        reversed.put(B, snapshot.participants().get(B)); reversed.put(A, snapshot.participants().get(A));
        RoundSnapshot copy = new RoundSnapshot(snapshot.roundId(), snapshot.sequence(), snapshot.seed(), snapshot.version(), snapshot.rules(),
                snapshot.phase(), snapshot.pauseReason(), snapshot.challengeNanos(), snapshot.remainingPhaseNanos(), reversed,
                snapshot.cleanShutdown(), snapshot.timingUncertain(), snapshot.result());
        byte[] bytes = RoundSnapshotCodec.encode(snapshot);
        assertArrayEquals(bytes, RoundSnapshotCodec.encode(copy));
        reversed.clear();
        assertEquals(2, copy.participants().size());
        assertEquals(snapshot, RoundSnapshotCodec.decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.participants().clear());
    }

    @Test void monotonicOriginCanBeNegativeButBackwardsClockFailsClosed() {
        clock.now = -900_000_000;
        RoundEngine engine = running();
        clock.millis(7_000);
        assertEquals(7_000, engine.view().challengeMillis());
        clock.now -= 8_000_000_000L;
        assertThrows(IllegalStateException.class, engine::view);
    }

    @Test void celebrationNormalRestartResumesRemainingServerTimeAfterDurableStartupBoundary() {
        RoundEngine engine = running();
        apply(engine, engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.COMPLETED, A, "verified hook")));
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        clock.millis(10_000);
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        clock.millis(10_000_000);
        RoundEngine restored = RoundEngine.restore(engine.committed(), clock);
        assertEquals(170_000, restored.view().remainingPhaseMillis());
        apply(restored, restored.proposeCheckpoint(UUID.randomUUID(), false));
        clock.millis(20_000);
        assertEquals(150_000, restored.view().remainingPhaseMillis());
        assertEquals(0, restored.view().challengeMillis());
    }

    @Test void inconsistentTerminalSnapshotCannotBeLoadedAsAUsableRace() {
        RoundSnapshot current = running().committed();
        assertThrows(IllegalArgumentException.class, () -> new RoundSnapshot(current.roundId(), current.sequence(), current.seed(), current.version(), current.rules(),
                RoundPhase.CELEBRATING, PauseReason.SETTLED, 0, 10, current.participants(), false, false, null));
        assertThrows(IllegalArgumentException.class, () -> new RoundSnapshot(current.roundId(), current.sequence(), current.seed(), current.version(), current.rules(),
                RoundPhase.RUNNING, PauseReason.NONE, 0, 0, current.participants(), false, false,
                new RoundResult(RoundResult.Kind.COMPLETED, A, "inconsistent imported row")));
    }

    @Test void lobbyOnlyParticipantCannotWinButOfflineHumanAttackKeepsAValidWinner() {
        RoundEngine engine = waiting();
        apply(engine, engine.proposeJoin(UUID.randomUUID(), A, "Alice"));
        apply(engine, engine.proposeJoin(UUID.randomUUID(), B, "Bob"));
        apply(engine, engine.proposeReady(UUID.randomUUID(), A, true));
        clock.millis(30_000);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> engine.proposeFinish(UUID.randomUUID(),
                new RoundResult(RoundResult.Kind.COMPLETED, B, "invalid lobby attribution")));
        assertFalse(engine.pending());
        apply(engine, engine.proposeOnline(UUID.randomUUID(), A, "Alice", false));
        apply(engine, engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.COMPLETED, A, "offline human projectile")));
        assertEquals(A, engine.committed().result().winner());
    }
}
