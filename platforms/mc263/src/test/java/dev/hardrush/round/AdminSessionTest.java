package dev.hardrush.round;

import dev.hardrush.core.config.*;
import dev.hardrush.core.record.AdminAudit;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SQLite with explicit server callback boundaries, including delayed writes and normal stop. */
final class AdminSessionTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    @BeforeEach void canonicalRoot() throws Exception { directory = directory.toRealPath(); }

    @Test void startRequiresAdaptersNormalModeAndReadyOnlineHumanThenAwaitsProfileAdmission() throws Exception {
        var path = directory.resolve("start.sqlite");
        var initial = seed(path, RoundPhase.WAITING, GameMode.NORMAL);
        var tasks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        try (var session = opened(path, clock, tasks, false)) {
            var reply = new AtomicReference<RoundSession.Reply>();
            session.adminStart("operator", "人工开赛", reply::set);
            assertFalse(reply.get().success());
            session.adaptersReady(initial.roundId()); pump(tasks, () -> !session.mutationPending());
            session.adminStart("operator", "人工开赛", reply::set);
            assertFalse(reply.get().success(), "An offline ready UUID cannot start");
            session.onOnline(player, "Human", true); pump(tasks, () -> !session.mutationPending());
            reply.set(null);
            session.adminStart("operator", "人工开赛", reply::set);
            assertNull(reply.get(), "Success waits for the transaction callback");
            pump(tasks, () -> reply.get() != null);
            assertTrue(reply.get().success(), reply.get().message());
            var running = session.snapshot().orElseThrow();
            assertEquals(RoundPhase.RUNNING, running.phase()); assertTrue(running.timingUncertain());
            assertFalse(running.participants().get(player).online(), "Durable human eligibility precedes unlocking");
            clock.set(TimeUnit.SECONDS.toNanos(10));
            assertEquals(0, session.view().orElseThrow().challengeNanos());
            session.onOnline(player, "Human", true); pump(tasks, () -> !session.mutationPending());
            clock.set(TimeUnit.SECONDS.toNanos(11));
            assertEquals(TimeUnit.SECONDS.toNanos(1), session.view().orElseThrow().challengeNanos());
        }
        try (var store = new SqliteRoundStore(path)) {
            var audit = store.audits(initial.roundId(), 20, 0);
            assertEquals(1, audit.size()); assertEquals("START", audit.getFirst().action());
            assertEquals("人工开赛", audit.getFirst().reason());
        }
    }

    @Test void invalidMetadataDoesNotLeaveAnInvisiblePendingCoreTransition() throws Exception {
        var path = directory.resolve("invalid.sqlite");
        seed(path, RoundPhase.WAITING, GameMode.NORMAL);
        var tasks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        try (var session = opened(path, clock, tasks, true)) {
            session.onOnline(player, "Human", true); pump(tasks, () -> !session.mutationPending());
            var reply = new AtomicReference<RoundSession.Reply>();
            session.adminStart("operator", " ", reply::set); assertFalse(reply.get().success());
            assertFalse(session.mutationPending());
            session.adminStart("😀".repeat(200), "合法原因", reply::set); assertFalse(reply.get().success());
            assertFalse(session.mutationPending(), "UTF-16 audit bounds must be validated before the core proposal");
            session.adminStart("operator", "合法原因", reply::set);
            pump(tasks, () -> !session.mutationPending());
            assertTrue(reply.get().success(), reply.get().message());
        }
    }

    @Test void abortLocksImmediatelyAndStopsAtAcceptanceDespiteIoDelayThenSafelyRotates() throws Exception {
        var path = directory.resolve("abort.sqlite"); var initial = seed(path, RoundPhase.RUNNING, GameMode.NORMAL);
        var tasks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        try (var session = opened(path, clock, tasks, true)) {
            session.onOnline(player, "Human", true); pump(tasks, () -> !session.mutationPending());
            clock.set(TimeUnit.SECONDS.toNanos(3));
            var reply = new AtomicReference<RoundSession.Reply>();
            try (var lock = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = lock.createStatement()) {
                sql.execute("BEGIN IMMEDIATE");
                session.adminAbort("operator", "测试中止", reply::set);
                assertTrue(session.competitionLocked()); assertNull(reply.get());
                clock.set(TimeUnit.SECONDS.toNanos(100));
                session.onOnline(player, "Human", false);
                session.normalDeath(player, 1);
                sql.execute("ROLLBACK");
            }
            pump(tasks, () -> reply.get() != null && !session.mutationPending());
            assertTrue(reply.get().success(), reply.get().message());
            var stopped = session.snapshot().orElseThrow();
            assertEquals(RoundPhase.ROTATING, stopped.phase()); assertEquals(RoundResult.Kind.ABORTED, stopped.result().kind());
            assertEquals(TimeUnit.SECONDS.toNanos(3), stopped.challengeNanos());
            assertEquals(ParticipantRole.HUMAN_ACTIVE, stopped.participants().get(player).role());
            session.rotationReady(initial.roundId()); pump(tasks, () -> !session.mutationPending());
            assertEquals(2, session.snapshot().orElseThrow().sequence());
        }
        try (var store = new SqliteRoundStore(path)) {
            assertEquals("ABORT", store.audits(initial.roundId(), 20, 0).getFirst().action());
            var history = store.roundHistory(initial.roundId()).orElseThrow();
            assertFalse(history.leaderboardEligible()); assertEquals("测试中止", history.result().reason());
            assertTrue(store.terminal(initial.roundId()).isEmpty(), "An abort never invents dragon evidence");
        }
    }

    @Test void stopDrainsAdminAbortWithoutCallbacksAndDoesNotAcceptLaterOnlineEvents() throws Exception {
        var path = directory.resolve("admin-stop.sqlite"); var initial = seed(path, RoundPhase.RUNNING, GameMode.NORMAL);
        var tasks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        var session = opened(path, clock, tasks, true);
        session.onOnline(player, "Human", true); pump(tasks, () -> !session.mutationPending());
        clock.set(TimeUnit.SECONDS.toNanos(3)); session.adminAbort("operator", "停服前中止", r -> {});
        session.prepareStop(); clock.set(TimeUnit.SECONDS.toNanos(100)); session.onOnline(player, "Human", false);
        session.close(); tasks.forEach(Runnable::run);
        try (var store = new SqliteRoundStore(path)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertTrue(saved.cleanShutdown()); assertEquals(TimeUnit.SECONDS.toNanos(3), saved.challengeNanos());
            assertEquals(RoundResult.Kind.ABORTED, saved.result().kind());
            assertEquals(1, store.audits(initial.roundId(), 20, 0).size());
        }
    }

    @Test void stopBoundaryRejectsNewConnectionEventsAndFailedViewDoesNotKeepTiming() throws Exception {
        var path = directory.resolve("stop-boundary.sqlite"); seed(path, RoundPhase.RUNNING, GameMode.NORMAL);
        var tasks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        var session = opened(path, clock, tasks, true);
        session.onOnline(player, "Human", true); pump(tasks, () -> !session.mutationPending());
        clock.set(TimeUnit.SECONDS.toNanos(3)); session.prepareStop();
        clock.set(TimeUnit.SECONDS.toNanos(100)); session.onOnline(player, "Human", false);
        assertFalse(session.mutationPending()); assertEquals(TimeUnit.SECONDS.toNanos(3), session.view().orElseThrow().challengeNanos());
        session.close();
        try (var store = new SqliteRoundStore(path)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertTrue(saved.cleanShutdown()); assertEquals(TimeUnit.SECONDS.toNanos(3), saved.challengeNanos());
        }
        var failedPath = directory.resolve("failed-view.sqlite"); seed(failedPath, RoundPhase.RUNNING, GameMode.NORMAL);
        clock.set(0);
        try (var failed = opened(failedPath, clock, tasks, true)) {
            failed.onOnline(player, "Human", true); pump(tasks, () -> !failed.mutationPending());
            clock.set(TimeUnit.SECONDS.toNanos(4)); failed.adapterFailure("测试故障", new IllegalStateException("retain"));
            clock.set(TimeUnit.SECONDS.toNanos(100));
            assertEquals(TimeUnit.SECONDS.toNanos(4), failed.view().orElseThrow().challengeNanos());
            assertTrue(failed.competitionLocked());
        }
    }

    @Test void worldRetryPersistsIntentBeforeAdapterAndCannotClearFailedSession() throws Exception {
        var path = directory.resolve("retry.sqlite"); var tasks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        try (var session = new RoundSession(path, clock::get, tasks::add)) {
            pump(tasks, () -> session.state() == RoundSession.State.OPEN);
            var round = session.snapshot().orElseThrow(); var invoked = new java.util.concurrent.atomic.AtomicBoolean();
            var reply = new AtomicReference<RoundSession.Reply>();
            var audit = new AdminAudit(UUID.randomUUID(), round.roundId(), "operator", "RETRY_WORLD", "重试原世界", "failed", "requested", 1);
            session.auditWorldRetry(audit, () -> invoked.set(true), reply::set);
            assertFalse(invoked.get()); pump(tasks, () -> reply.get() != null);
            assertTrue(invoked.get()); assertTrue(reply.get().success());
            var rows = session.audits(round.roundId(), 20, 0); pump(tasks, rows::isDone); assertEquals(1, rows.join().size());
            session.adapterFailure("保留故障", new IllegalStateException("retain")); invoked.set(false);
            session.auditWorldRetry(audit, () -> invoked.set(true), reply::set);
            assertFalse(reply.get().success()); assertFalse(invoked.get()); assertEquals(RoundSession.State.FAILED, session.state());
        }
    }

    @Test void revivalGrantCommitsCountsAndAuditTogetherAndPreservesBalanceOnReopen() throws Exception {
        var path = directory.resolve("grant-revival.sqlite");
        var initial = seed(path, RoundPhase.RUNNING, GameMode.HARDCORE);
        var tasks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        try (var session = opened(path, clock, tasks, true)) {
            var reply = new AtomicReference<RoundSession.Reply>();
            session.adminGrantRevival(initial.roundId(), player, 1, 0, 3, "operator", "测试补偿", reply::set);
            assertNull(reply.get(), "Grant success must await SQLite commit and main-thread callback");
            assertEquals(0, session.snapshot().orElseThrow().participants().get(player).revivalCharges());
            pump(tasks, () -> reply.get() != null);
            assertTrue(reply.get().success(), reply.get().message());
            assertEquals(3, session.snapshot().orElseThrow().participants().get(player).revivalCharges());
            assertTrue(session.snapshot().orElseThrow().timingUncertain());
            assertEquals(ParticipantRole.HUMAN_ACTIVE, session.snapshot().orElseThrow().participants().get(player).role());
            session.adminGrantRevival(initial.roundId(), player, 1, 0, 3, "operator", "旧余额重放", reply::set);
            assertFalse(reply.get().success());
            assertFalse(session.mutationPending());
            var rows = session.audits(initial.roundId(), 20, 0); pump(tasks, rows::isDone);
            assertEquals(1, rows.join().size());
            var audit = rows.join().getFirst();
            assertEquals("GRANT_REVIVAL", audit.action());
            assertTrue(audit.beforeValue().contains("player=" + player + ",revivalCharges=0"));
            assertTrue(audit.afterValue().contains("player=" + player + ",revivalCharges=3"));
            var records = session.participants(initial.roundId(), 20, 0); pump(tasks, records::isDone);
            assertEquals(0, records.join().getFirst().revivalCount(), "Granting credits is not an actual revival");
        }
        try (var reopened = opened(path, clock, tasks, true)) {
            assertEquals(3, reopened.snapshot().orElseThrow().participants().get(player).revivalCharges());
            assertTrue(reopened.snapshot().orElseThrow().timingUncertain());
        }
    }

    @Test void failedRevivalGrantAuditRollsBackBalanceAndNeverReportsSuccess() throws Exception {
        var path = directory.resolve("grant-revival-failed.sqlite");
        var initial = seed(path, RoundPhase.RUNNING, GameMode.HARDCORE);
        var tasks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        try (var session = opened(path, clock, tasks, true);
                var raw = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = raw.createStatement()) {
            sql.execute("CREATE TRIGGER fail_grant BEFORE INSERT ON admin_audits BEGIN SELECT RAISE(ABORT,'injected'); END");
            var reply = new AtomicReference<RoundSession.Reply>();
            session.adminGrantRevival(initial.roundId(), player, 1, 0, 3, "operator", "失败补偿测试", reply::set);
            pump(tasks, () -> reply.get() != null);
            assertFalse(reply.get().success());
            assertEquals(RoundSession.State.FAILED, session.state());
            assertEquals(0, session.snapshot().orElseThrow().participants().get(player).revivalCharges());
        }
        try (var store = new SqliteRoundStore(path)) {
            assertEquals(0, RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).participants().get(player).revivalCharges());
            assertTrue(store.audits(initial.roundId(), 20, 0).isEmpty());
        }
    }

    private RoundSnapshot seed(Path path, RoundPhase phase, GameMode mode) throws Exception {
        var role = phase == RoundPhase.RUNNING ? ParticipantRole.HUMAN_ACTIVE : ParticipantRole.LOBBY;
        var round = new RoundSnapshot(UUID.randomUUID(), 1, 12345, 0, RulesConfig.defaults().withMode(mode), phase,
                phase == RoundPhase.RUNNING ? PauseReason.SERVER_STOPPED : PauseReason.NOT_RUNNING, 0, 0,
                Map.of(player, new Participant(player, "Human", role, true, phase != RoundPhase.RUNNING, 0,
                        phase == RoundPhase.RUNNING ? 0 : -1, phase == RoundPhase.RUNNING ? 1 : 0, false)), true, false, null);
        try (var store = new SqliteRoundStore(path)) {
            store.savePendingRules(round.rules().encode().getBytes(java.nio.charset.StandardCharsets.UTF_8), UUID.randomUUID(), "fixture");
            store.commit(new SnapshotWrite(round.roundId(), -1, 0, RoundSnapshotCodec.encode(round), UUID.randomUUID(), "INITIAL"));
        }
        return round;
    }
    private static RoundSession opened(Path path, AtomicLong clock, LinkedBlockingQueue<Runnable> tasks, boolean adapters) throws Exception {
        var session = new RoundSession(path, clock::get, tasks::add); pump(tasks, () -> session.state() == RoundSession.State.OPEN);
        if (adapters) { session.adaptersReady(session.snapshot().orElseThrow().roundId()); pump(tasks, () -> !session.mutationPending()); }
        return session;
    }
    private static void pump(LinkedBlockingQueue<Runnable> tasks, BooleanSupplier complete) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!complete.getAsBoolean() && System.nanoTime() < deadline) { var task = tasks.poll(20, TimeUnit.MILLISECONDS); if (task != null) task.run(); }
        assertTrue(complete.getAsBoolean(), "Server callbacks did not finish");
    }
}
