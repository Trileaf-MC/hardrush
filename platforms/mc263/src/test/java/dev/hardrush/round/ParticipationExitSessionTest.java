package dev.hardrush.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParticipationExitSessionTest {
    @TempDir Path directory;
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private Path seed() throws Exception {
        directory = directory.toRealPath();
        var path = directory.resolve("rounds.sqlite");
        var rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
        var round = new RoundSnapshot(UUID.randomUUID(), 1, 123, 0, rules, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                0, 0, Map.of(a, new Participant(a, "Alice", ParticipantRole.HUMAN_ACTIVE, false, false, 12, 0, 1, false),
                    b, new Participant(b, "Bob", ParticipantRole.HUMAN_ACTIVE, false, false, 34, 0, 1, false)), true, false, null);
        try (var store = new SqliteRoundStore(path)) {
            store.savePendingRules(rules.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8), UUID.randomUUID(), "fixture");
            store.commit(new SnapshotWrite(round.roundId(), -1, 0, RoundSnapshotCodec.encode(round), UUID.randomUUID(), "INITIAL"));
        }
        return path;
    }
    private RoundSession open(Path path, LinkedBlockingQueue<Runnable> callbacks) throws Exception {
        var session = new RoundSession(path, () -> 0, callbacks::add);
        pump(callbacks, () -> session.state() == RoundSession.State.OPEN && !session.mutationPending());
        session.adaptersReady(session.snapshot().orElseThrow().roundId());
        pump(callbacks, () -> !session.mutationPending());
        return session;
    }
    private void exit(RoundSession session, ParticipantRole destination, String actor, String reason,
            AtomicReference<RoundSession.Reply> reply) {
        var round = session.snapshot().orElseThrow(); var p = round.participants().get(a);
        session.requestParticipationExit(round.roundId(), a, p.role(), p.lifeSequence(), p.monsterDeploymentSequence(),
                destination, actor, reason, reply::set);
    }
    @Test void anOfflineAutomaticSanctionAndItsAuditSurviveRestart() throws Exception {
        var path = seed(); var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var session = open(path, callbacks)) {
            var reply = new AtomicReference<RoundSession.Reply>();
            exit(session, ParticipantRole.DISQUALIFIED, "system:anti-cheat", "规则检测到作弊", reply);
            assertTrue(session.participationExitReserved(a));
            assertEquals(ParticipantRole.HUMAN_ACTIVE, session.snapshot().orElseThrow().participants().get(a).role());
            pump(callbacks, () -> reply.get() != null);
            assertTrue(reply.get().success()); assertFalse(session.participationExitReserved(a));
            var audits = query(callbacks, session.audits(null, 20, 0));
            assertEquals(1, audits.size());
            assertEquals("system:anti-cheat", audits.getFirst().actor());
            assertEquals("DISQUALIFY", audits.getFirst().action());
            assertTrue(audits.getFirst().beforeValue().contains(a.toString()));
            assertTrue(audits.getFirst().afterValue().contains("DISQUALIFIED"));
        }
        try (var session = open(path, callbacks)) {
            assertEquals(ParticipantRole.DISQUALIFIED, session.snapshot().orElseThrow().participants().get(a).role());
            assertEquals(1, query(callbacks, session.audits(null, 20, 0)).size());
        }
    }
    @Test void voluntaryExitIsAuditedWithoutMarkingTheRoundAsCheatingAndDuplicatesDoNotWriteAgain() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var session = open(seed(), callbacks)) {
            var reply = new AtomicReference<RoundSession.Reply>();
            exit(session, ParticipantRole.WITHDRAWN, "player:" + a, "主动观战", reply);
            pump(callbacks, () -> reply.get() != null);
            assertTrue(reply.get().success()); assertFalse(session.snapshot().orElseThrow().timingUncertain());
            reply.set(null); exit(session, ParticipantRole.WITHDRAWN, "player:" + a, "重复点击", reply);
            assertFalse(reply.get().success());
            var audits = query(callbacks, session.audits(null, 20, 0));
            assertEquals(1, audits.size()); assertEquals("WITHDRAW", audits.getFirst().action());
        }
    }
    @Test void failedStorageNeverReportsSuccessOrAppliesAnUncommittedExclusion() throws Exception {
        var path = seed(); var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var session = open(path, callbacks)) {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER participation_exit_failure BEFORE INSERT ON admin_audits "
                        + "BEGIN SELECT RAISE(ABORT, 'intentional audit storage failure'); END");
            }
            var reply = new AtomicReference<RoundSession.Reply>();
            exit(session, ParticipantRole.DISQUALIFIED, "system:anti-cheat", "存储故障夹具", reply);
            pump(callbacks, () -> reply.get() != null);
            assertFalse(reply.get().success());
            assertEquals(RoundSession.State.FAILED, session.state());
            assertEquals(ParticipantRole.HUMAN_ACTIVE, session.snapshot().orElseThrow().participants().get(a).role());
            try (var store = new SqliteRoundStore(path)) {
                assertEquals(ParticipantRole.HUMAN_ACTIVE, RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).participants().get(a).role());
                assertTrue(store.audits(null, 20, 0).isEmpty(), "The snapshot and audit both roll back");
            }
        }
    }
    @Test void blankReasonCannotCreateAReservationOrAnAudit() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var session = open(seed(), callbacks)) {
            var reply = new AtomicReference<RoundSession.Reply>();
            exit(session, ParticipantRole.DISQUALIFIED, "admin:fixture", " ", reply);
            assertFalse(reply.get().success()); assertFalse(session.participationExitReserved(a));
            assertTrue(query(callbacks, session.audits(null, 20, 0)).isEmpty());
        }
    }
    @Test void aSanctionUpgradeAlsoSealsTheAlreadyWithdrawnObserverBeforeCommit() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var session = open(seed(), callbacks)) {
            var reply = new AtomicReference<RoundSession.Reply>();
            exit(session, ParticipantRole.WITHDRAWN, "player:" + a, "主动观战", reply);
            pump(callbacks, () -> reply.get() != null);
            reply.set(null);
            exit(session, ParticipantRole.DISQUALIFIED, "system:anti-cheat", "退出后追加查实处罚", reply);
            assertTrue(session.participationExitReserved(a));
            assertEquals(ParticipantRole.WITHDRAWN, session.snapshot().orElseThrow().participants().get(a).role());
            pump(callbacks, () -> reply.get() != null);
            assertTrue(reply.get().success()); assertFalse(session.participationExitReserved(a));
            assertEquals(ParticipantRole.DISQUALIFIED, session.snapshot().orElseThrow().participants().get(a).role());
            assertEquals(2, query(callbacks, session.audits(null, 20, 0)).size());
        }
    }
    private static <T> T query(LinkedBlockingQueue<Runnable> tasks, CompletableFuture<T> future) throws Exception {
        pump(tasks, future::isDone); return future.join();
    }
    private static void pump(LinkedBlockingQueue<Runnable> tasks, BooleanSupplier complete) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!complete.getAsBoolean() && System.nanoTime() < deadline) {
            var task = tasks.poll(20, TimeUnit.MILLISECONDS); if (task != null) task.run();
        }
        assertTrue(complete.getAsBoolean(), "Server callbacks did not finish");
    }
}
