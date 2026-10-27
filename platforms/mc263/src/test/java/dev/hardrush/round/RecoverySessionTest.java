package dev.hardrush.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.*;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real SQLite repair/decision transactions with a deliberately separate main-thread callback queue. */
final class RecoverySessionTest {
    @TempDir Path temporary;
    private final UUID player = UUID.randomUUID();
    private final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
    private final AtomicLong clock = new AtomicLong();

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void closingRecoveryPreservesCommittedEntitlementAndLifeWithoutReplayingConsumption(boolean awarded) throws Exception {
        Path database = database("identity");
        var initial = seed(database, awarded, false);
        try (var session = open(database)) {
            var quarantined = session.snapshot().orElseThrow();
            assertEquals(RoundPhase.RECOVERY_REQUIRED, quarantined.phase());
            var reply = closeRecovery(session);
            assertTrue(reply.success(), reply.message());
            var retired = session.snapshot().orElseThrow();
            assertEquals(RoundPhase.ROTATING, retired.phase());
            assertEquals(RoundResult.Kind.ABORTED, retired.result().kind());
            assertEquals(quarantined.participants(), retired.participants());
            assertEquals(initial.challengeNanos(), retired.challengeNanos());
            assertTrue(retired.timingUncertain());
            clock.addAndGet(TimeUnit.DAYS.toNanos(10));
            assertEquals(retired.challengeNanos(), session.view().orElseThrow().challengeNanos());
            var repeated = new AtomicReference<RoundSession.Reply>();
            session.closeRecovery("operator", "重复结束", repeated::set);
            assertFalse(repeated.get().success());
        }
        try (var store = new SqliteRoundStore(database)) {
            assertEquals(1, store.audits(initial.roundId(), 10, 0).size());
            assertEquals("CLOSE_RECOVERY", store.audits(initial.roundId(), 10, 0).getFirst().action());
            assertFalse(store.roundHistory(initial.roundId()).orElseThrow().leaderboardEligible());
            assertTrue(store.terminal(initial.roundId()).isEmpty());
            assertEquals(awarded ? 0 : 1, store.participantRecord(initial.roundId(), player).orElseThrow().revivalCount());
        }
        try (var reopened = open(database)) {
            assertEquals(RoundPhase.ROTATING, reopened.snapshot().orElseThrow().phase(), "A normal stop retains the decision");
            assertEquals(initial.roundId(), reopened.snapshot().orElseThrow().roundId());
        }
    }

    @Test void closingCommittedCompletionKeepsOriginalTerminalHistoryAndLeaderboardByteForByte() throws Exception {
        Path database = database("terminal"); var initial = seed(database, false, true);
        byte[] terminal; dev.hardrush.core.record.RoundHistory history;
        try (var store = new SqliteRoundStore(database)) {
            terminal = store.terminal(initial.roundId()).orElseThrow().snapshot().payload();
            history = store.roundHistory(initial.roundId()).orElseThrow();
            assertTrue(history.leaderboardEligible());
        }
        try (var session = open(database)) {
            session.adapterFailure("终局后存储故障", new IllegalStateException("retain original evidence"));
            assertTrue(recheck(session).success());
            assertTrue(closeRecovery(session).success());
            assertEquals(history.result(), session.snapshot().orElseThrow().result());
        }
        try (var store = new SqliteRoundStore(database)) {
            assertArrayEquals(terminal, store.terminal(initial.roundId()).orElseThrow().snapshot().payload());
            assertEquals(history, store.roundHistory(initial.roundId()).orElseThrow());
            assertTrue(store.historyRevisions(initial.roundId(), 10, 0).isEmpty());
            assertEquals(1, store.evidence(initial.roundId(), "COMBAT", 10).size());
        }
    }

    @Test void recoveryDecisionAndAuditRollBackTogetherOnRealSqliteWriteFailureThenRepairWorks() throws Exception {
        Path database = database("rollback"); var initial = seed(database, true, false);
        var session = open(database);
        try {
            sql(database, "CREATE TRIGGER refuse_audit BEFORE INSERT ON admin_audits BEGIN SELECT RAISE(ABORT,'storage failure'); END");
            assertFalse(closeRecovery(session).success());
            assertEquals(RoundSession.State.FAILED, session.state());
            try (var store = new SqliteRoundStore(database)) {
                assertArrayEquals(RoundSnapshotCodec.encode(initial), store.latest().orElseThrow().payload());
                assertTrue(store.audits(null, 10, 0).isEmpty()); assertTrue(store.history(10, 0).isEmpty());
            }
            assertFalse(recheck(session).success(), "The unchanged storage fault must not report successful repair");
            assertEquals(RoundSession.State.FAILED, session.state());
            sql(database, "DROP TRIGGER refuse_audit");
            assertTrue(recheck(session).success());
            assertEquals(RoundPhase.RECOVERY_REQUIRED, session.snapshot().orElseThrow().phase());
            assertTrue(session.competitionLocked());
            assertEquals(initial.participants().get(player).revivalEntitled(), session.snapshot().orElseThrow().participants().get(player).revivalEntitled());
            assertTrue(closeRecovery(session).success());
            try (var store = new SqliteRoundStore(database)) {
                assertEquals(List.of("CLOSE_RECOVERY", "RECHECK_STORAGE"), store.audits(null, 10, 0).stream().map(a -> a.action()).toList());
            }
        } finally { session.close(); }
    }

    @Test void repairUsesLatestDurableStateRatherThanStaleEngineAfterWithheldCallback() throws Exception {
        Path database = database("delayed"); seed(database, false, false);
        try (var session = open(database)) {
            var before = session.snapshot().orElseThrow();
            var reply = new AtomicReference<RoundSession.Reply>();
            session.closeRecovery("operator", "关闭原轮", reply::set);
            // The callback is withheld: wait on a separate connection for the actual SQLite commit.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            RoundSnapshot durable = before;
            while (durable.version() == before.version() && System.nanoTime() < deadline) {
                try (var store = new SqliteRoundStore(database)) { durable = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()); }
                if (durable.version() == before.version()) Thread.sleep(10);
            }
            assertEquals(before.version() + 1, durable.version());
            session.adapterFailure("回调前原生故障", new IllegalStateException("native effect incomplete"));
            var blocked = new AtomicReference<RoundSession.Reply>();
            session.recheckStorage("operator", "先等回调", blocked::set);
            assertFalse(blocked.get().success()); assertTrue(blocked.get().message().contains("排空"));
            pump(() -> !session.mutationPending());
            assertTrue(recheck(session).success());
            var repaired = session.snapshot().orElseThrow();
            assertEquals(durable.result(), repaired.result());
            assertEquals(durable.version() + 1, repaired.version());
            assertEquals(RoundPhase.RECOVERY_REQUIRED, repaired.phase());
        }
    }

    @Test void missingDatabaseCannotBeSilentlyRecreatedByRepair() throws Exception {
        Path database = database("missing"); seed(database, false, false);
        try (var session = open(database)) {
            session.adapterFailure("介质故障", new IllegalStateException("missing"));
            // Close the fixture connection before moving the whole database; never alter a live WAL set.
            sessionStore(session).close();
            Path retained = database.resolveSibling("retained.sqlite");
            java.nio.file.Files.move(database, retained);
            assertFalse(recheck(session).success());
            assertFalse(java.nio.file.Files.exists(database));
            assertTrue(java.nio.file.Files.isRegularFile(retained));
        }
    }

    @Test void foreignOrRolledBackLatestRoundCannotReplaceTheFailedTarget() throws Exception {
        Path database = database("foreign"); seed(database, false, false);
        try (var session = open(database)) {
            session.adapterFailure("并发错误", new IllegalStateException("retain"));
            var original = session.snapshot().orElseThrow();
            try (var store = new SqliteRoundStore(database)) {
                var foreign = RoundEngine.create(UUID.randomUUID(), original.sequence() + 1, 5, original.rules(), () -> 0).committed();
                store.commit(new SnapshotWrite(foreign.roundId(), -1, 0, RoundSnapshotCodec.encode(foreign), UUID.randomUUID(), "FOREIGN"));
            }
            assertFalse(recheck(session).success()); assertEquals(original, session.snapshot().orElseThrow());
            assertEquals(RoundSession.State.FAILED, session.state());
        }
    }

    @Test void normalStopDrainsRepairWithoutMainThreadCallbackAndRestartStaysQuarantined() throws Exception {
        Path database = database("stop"); var initial = seed(database, true, false);
        var session = open(database);
        session.adapterFailure("故障", new IllegalStateException("retain"));
        session.recheckStorage("operator", "已修复存储", r -> fail("Stop must not execute the reply callback"));
        assertEquals(RoundSession.State.RECHECKING, session.state());
        session.prepareStop(); session.close(); callbacks.forEach(Runnable::run); callbacks.clear();
        try (var reopened = open(database)) {
            assertEquals(RoundPhase.RECOVERY_REQUIRED, reopened.snapshot().orElseThrow().phase());
            assertEquals(initial.challengeNanos(), reopened.view().orElseThrow().challengeNanos());
            assertTrue(reopened.competitionLocked());
        }
        try (var store = new SqliteRoundStore(database)) { assertEquals(1, store.audits(null, 10, 0).size()); }
    }

    @Test void activeRoundsAndInvalidMetadataNeverStartRecoveryWrites() throws Exception {
        Path database = database("invalid"); seed(database, true, false);
        try (var session = open(database)) {
            var result = new AtomicReference<RoundSession.Reply>();
            session.recheckStorage("operator", "无需核验", result::set); assertFalse(result.get().success());
            session.closeRecovery("operator", " ", result::set); assertFalse(result.get().success());
            assertFalse(session.mutationPending());
            session.adapterFailure("故障", new IllegalStateException("retain"));
            session.recheckStorage("😀".repeat(200), "已修复", result::set); assertFalse(result.get().success());
            assertFalse(session.mutationPending());
        }
    }

    @Test void integrityFailureCannotBeClearedByAWriteableDatabaseOrProduceAnAudit() throws Exception {
        Path database = database("integrity"); seed(database, true, false);
        try (var session = open(database)) {
            session.adapterFailure("损坏的引用", new IllegalStateException("retain"));
            sql(database, "CREATE TABLE p3_parent(id INTEGER PRIMARY KEY)");
            sql(database, "CREATE TABLE p3_child(parent INTEGER REFERENCES p3_parent(id))");
            sql(database, "INSERT INTO p3_child VALUES (9)");
            assertFalse(recheck(session).success()); assertEquals(RoundSession.State.FAILED, session.state());
            try (var store = new SqliteRoundStore(database)) { assertTrue(store.audits(null, 10, 0).isEmpty()); }
            sql(database, "DELETE FROM p3_child");
            assertTrue(recheck(session).success()); assertTrue(session.competitionLocked());
        }
    }

    private Path database(String name) throws Exception { return temporary.toRealPath().resolve(name + ".sqlite"); }
    private RoundSnapshot seed(Path database, boolean entitled, boolean terminal) throws Exception {
        var participant = new Participant(player, "Player", entitled ? ParticipantRole.MONSTER_LOBBY : ParticipantRole.HUMAN_ACTIVE,
                false, false, 10_000, 0, entitled ? 1 : 2, entitled);
        var running = new RoundSnapshot(UUID.randomUUID(), 1, 12345, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.NO_ONLINE_HUMANS, 10_000, 0, Map.of(player, participant), false, false, null);
        try (var store = new SqliteRoundStore(database)) {
            store.savePendingRules(running.rules().encode().getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), "fixture");
            store.commit(new SnapshotWrite(running.roundId(), -1, 0, RoundSnapshotCodec.encode(running), UUID.randomUUID(), "INITIAL"));
            if (!terminal) return running;
            var engine = RoundEngine.restore(new RoundSnapshot(running.roundId(), 1, running.seed(), 0, running.rules(), running.phase(),
                    running.pauseReason(), running.challengeNanos(), 0, running.participants(), true, false, null), () -> 0);
            UUID dragon = UUID.randomUUID();
            var origin = new CombatOrigin(running.roundId(), UUID.randomUUID(), new CombatActor(player, ParticipantRole.HUMAN_ACTIVE, 2, 0),
                    AttackKind.MELEE, Optional.of(player), List.of());
            var evidence = new CombatEvent(running.roundId(), UUID.randomUUID(), UUID.randomUUID(), 1, dragon, Optional.of(origin),
                    CombatEvent.Attribution.HUMAN, "minecraft:player_attack", Optional.of(player), Optional.of(player), 1, true, 0);
            var finish = engine.proposeFinish(UUID.randomUUID(), DragonDeathArbiter.adjudicate(engine.committed(), evidence, dragon));
            store.commitTerminal(new SnapshotWrite(running.roundId(), 0, 1, RoundSnapshotCodec.encode(finish.after()), finish.eventId(), finish.eventType()),
                    new EvidenceWrite(running.roundId(), evidence.eventId(), "COMBAT", CombatEventCodec.encode(evidence)), dragon);
            return finish.after();
        }
    }
    private RoundSession open(Path database) throws Exception {
        var session = new RoundSession(database, clock::get, callbacks::add);
        pump(() -> session.state() == RoundSession.State.OPEN); return session;
    }
    private RoundSession.Reply closeRecovery(RoundSession session) throws Exception {
        var reply = new AtomicReference<RoundSession.Reply>();
        session.closeRecovery("operator", "人工核对后结束原轮", reply::set); pump(() -> reply.get() != null); return reply.get();
    }
    private RoundSession.Reply recheck(RoundSession session) throws Exception {
        var reply = new AtomicReference<RoundSession.Reply>();
        session.recheckStorage("operator", "存储已修复，重新核验", reply::set); pump(() -> reply.get() != null); return reply.get();
    }
    private void pump(BooleanSupplier complete) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!complete.getAsBoolean() && System.nanoTime() < deadline) { var task = callbacks.poll(20, TimeUnit.MILLISECONDS); if (task != null) task.run(); }
        assertTrue(complete.getAsBoolean(), "IO callbacks did not complete");
    }
    private static void sql(Path database, String query) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) { statement.execute(query); }
    }
    private static SqliteRoundStore sessionStore(RoundSession session) throws Exception {
        var field = RoundSession.class.getDeclaredField("store"); field.setAccessible(true); return (SqliteRoundStore) field.get(session);
    }
}
