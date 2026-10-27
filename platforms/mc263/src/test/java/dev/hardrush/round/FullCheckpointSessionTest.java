package dev.hardrush.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullCheckpointSessionTest {
    @TempDir Path root;

    @Test void verifiedRollbackWaitsForAdaptersAndDurableUncertaintyBeforeOpeningPlay() throws Exception {
        root = root.toRealPath();
        var database = root.toRealPath().resolve("p1.sqlite");
        var player = UUID.randomUUID(); var round = running(player, false);
        try (var store = new SqliteRoundStore(database)) { write(store, round); }
        var callbacks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        try (var session = new RoundSession(database, clock::get, callbacks::add, (message, error) -> {}, true, "自动恢复完整保存点")) {
            pump(callbacks, () -> session.state() != RoundSession.State.OPENING);
            assertEquals(RoundPhase.RUNNING, session.snapshot().orElseThrow().phase());
            assertTrue(session.competitionLocked());
            assertTrue(session.snapshot().orElseThrow().timingUncertain());
            assertFalse(session.snapshot().orElseThrow().participants().get(player).online());
            clock.addAndGet(TimeUnit.HOURS.toNanos(2));
            assertEquals(round.challengeNanos(), session.view().orElseThrow().challengeNanos());
            session.adaptersReady(round.roundId());
            assertTrue(session.competitionLocked(), "SQLite callback must commit the review marker first");
            pump(callbacks, () -> !session.mutationPending());
            assertFalse(session.competitionLocked());
            assertTrue(SqliteRoundStore.inspectLatest(database).orElseThrow().timingUncertain());
            session.onOnline(player, "Recovered", true);
            pump(callbacks, () -> !session.mutationPending());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            long beforeFailedAttempt = session.view().orElseThrow().challengeNanos();
            assertTrue(session.beginFullCheckpoint());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(4));
            session.endFullCheckpoint();
            assertEquals(beforeFailedAttempt, session.view().orElseThrow().challengeNanos(), "A failure before export preserves time already raced");
            assertTrue(session.beginFullCheckpoint());
            Path exported = root.resolve("copy.sqlite");
            var saved = session.exportFullCheckpoint(exported);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(4));
            session.endFullCheckpoint();
            assertEquals(saved, SqliteRoundStore.inspectLatest(exported).orElseThrow());
            assertEquals(saved.challengeNanos(), session.view().orElseThrow().challengeNanos());
        }
    }

    @Test void failedRestoreReasonCannotAccidentallyUnlockEvenACleanPartialReplacement() throws Exception {
        var database = root.toRealPath().resolve("p1.sqlite"); var round = running(UUID.randomUUID(), true);
        try (var store = new SqliteRoundStore(database)) { write(store, round); }
        var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var session = new RoundSession(database, () -> 0, callbacks::add, (message, error) -> {}, false, "保存点文件损坏")) {
            pump(callbacks, () -> session.state() != RoundSession.State.OPENING);
            assertEquals(RoundPhase.RECOVERY_REQUIRED, session.snapshot().orElseThrow().phase());
            session.adaptersReady(round.roundId());
            assertTrue(session.competitionLocked()); assertFalse(session.beginFullCheckpoint());
            assertTrue(session.status().contains("保存点文件损坏"));
        }
    }

    @Test void onlineReloadKeepsSessionBindingsAndPersistsRecoveryAuditBeforeUnlock() throws Exception {
        var database = root.toRealPath().resolve("p1.sqlite"); var round = running(UUID.randomUUID(), false);
        try (var store = new SqliteRoundStore(database)) { write(store, round); }
        var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var session = new RoundSession(database, () -> 0, callbacks::add)) {
            pump(callbacks, () -> session.state() != RoundSession.State.OPENING);
            assertTrue(session.canRetryRecovery());
            session.suspendForOnlineRecovery();
            assertEquals(RoundSession.State.RECHECKING, session.state());
            assertTrue(session.competitionLocked());
            session.reloadOnlineCheckpoint(round, "console:test", 1234);
            assertEquals(RoundPhase.RUNNING, session.snapshot().orElseThrow().phase());
            assertTrue(session.competitionLocked());
            try (var store = new SqliteRoundStore(database)) {
                var rows = store.audits(round.roundId(), 20, 0);
                assertEquals(1, rows.size()); assertEquals("RECOVER_CHECKPOINT", rows.getFirst().action());
                assertTrue(SqliteRoundStore.inspectLatest(database).orElseThrow().timingUncertain());
            }
            session.adaptersReady(round.roundId());
            pump(callbacks, () -> !session.mutationPending());
            assertFalse(session.competitionLocked());
            assertFalse(session.canRetryRecovery(), "normal matches must not be rewound by this command");
        }
    }

    private static RoundSnapshot running(UUID player, boolean clean) {
        return new RoundSnapshot(UUID.randomUUID(), 1, 123, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.NONE, 500, 0, Map.of(player, new Participant(player, "Recovered", ParticipantRole.HUMAN_ACTIVE,
                        true, false, 300, 0, 1, false)), clean, false, null);
    }
    private static void write(SqliteRoundStore store, RoundSnapshot round) throws Exception {
        assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(round.roundId(), -1, 0,
                RoundSnapshotCodec.encode(round), UUID.randomUUID(), "ROUND_CREATED")));
    }
    private static void pump(LinkedBlockingQueue<Runnable> callbacks, BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "SQLite callback timeout");
            var task = callbacks.poll(20, TimeUnit.MILLISECONDS); if (task != null) task.run();
        }
    }
}
