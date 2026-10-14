package dev.hardrush.p1;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.PauseReason;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundSnapshotCodec;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite IO with a manually pumped server callback queue; no Minecraft client. */
final class P1LobbySessionTest {
    @TempDir Path directory;

    @BeforeEach
    void canonicalizeTemporaryRoot() throws Exception {
        // macOS exposes the temp directory through /var -> /private/var.
        // Resolve the test fixture; production still rejects symlink parents.
        directory = directory.toRealPath();
    }

    @Test
    void createsPreparingRoundAndRestoresSameIdAfterCleanStopWithoutDowntime() throws Exception {
        var database = directory.resolve("hardrush/p1.sqlite");
        var clock = new AtomicLong(5_000_000_000L);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        UUID roundId;
        long stoppedVersion;
        try (var session = new P1LobbySession(database, clock::get, callbacks::add)) {
            assertEquals(P1LobbySession.State.OPENING, session.state());
            assertTrue(session.snapshot().isEmpty());
            pumpUntil(callbacks, () -> session.state() != P1LobbySession.State.OPENING);
            assertEquals(P1LobbySession.State.OPEN, session.state(), session.problem());
            var round = session.snapshot().orElseThrow();
            roundId = round.roundId();
            assertEquals(RoundPhase.PREPARING, round.phase());
            assertFalse(round.cleanShutdown());
            assertFalse(round.timingUncertain());
            assertEquals(0, round.version());
            assertTrue(round.participants().isEmpty());
            assertFalse(session.requestJoin(UUID.randomUUID(), "Tester").success());
            assertFalse(session.requestReady(UUID.randomUUID(), true).success());
            assertFalse(session.requestReady(UUID.randomUUID(), false).success());
            assertTrue(session.snapshot().orElseThrow().participants().isEmpty());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            session.tick();
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(1, session.snapshot().orElseThrow().version());
            assertEquals(0, session.snapshot().orElseThrow().challengeNanos());
        }
        try (var store = new SqliteRoundStore(database)) {
            var stopped = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertTrue(stopped.cleanShutdown());
            assertEquals(roundId, stopped.roundId());
            stoppedVersion = stopped.version();
        }
        clock.addAndGet(TimeUnit.DAYS.toNanos(100));
        try (var reopened = new P1LobbySession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> reopened.state() != P1LobbySession.State.OPENING);
            var restored = reopened.snapshot().orElseThrow();
            assertEquals(P1LobbySession.State.OPEN, reopened.state(), reopened.problem());
            assertEquals(roundId, restored.roundId());
            assertEquals(RoundPhase.PREPARING, restored.phase());
            assertEquals(stoppedVersion + 1, restored.version());
            assertFalse(restored.cleanShutdown(), "session must durably mark resumed operation dirty before opening");
            assertFalse(restored.timingUncertain());
            assertEquals(0, restored.challengeNanos());
            assertEquals(0, restored.remainingPhaseNanos());
        }
    }

    @Test
    void nextModeAcknowledgementWaitsForSqliteAndDoesNotMutateCurrentRules() throws Exception {
        var database = directory.resolve("mode.sqlite");
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var answer = new AtomicReference<P1LobbySession.Reply>();
        try (var session = new P1LobbySession(database, () -> 0, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != P1LobbySession.State.OPENING);
            assertEquals(GameMode.HARDCORE, session.nextRules().orElseThrow().mode());
            session.updateNextMode(GameMode.NORMAL, "operator-test", answer::set);
            assertNull(answer.get());
            assertEquals(GameMode.HARDCORE, session.nextRules().orElseThrow().mode());
            assertTrue(session.mutationPending());
            pumpUntil(callbacks, () -> answer.get() != null);
            assertTrue(answer.get().success(), answer.get().message());
            assertEquals(GameMode.NORMAL, session.nextRules().orElseThrow().mode());
            assertEquals(GameMode.HARDCORE, session.snapshot().orElseThrow().rules().mode());
        }
        try (var store = new SqliteRoundStore(database)) {
            var savedRules = RulesConfig.decode(new String(store.loadPendingRules().orElseThrow(), StandardCharsets.UTF_8));
            assertEquals(GameMode.NORMAL, savedRules.mode());
            assertEquals(GameMode.HARDCORE, RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).rules().mode());
        }
    }

    @Test
    void writeLockFailureDoesNotPublishSuccessfulModeOrChangePendingConfig() throws Exception {
        var database = directory.resolve("locked.sqlite");
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var answer = new AtomicReference<P1LobbySession.Reply>();
        try (var session = new P1LobbySession(database, () -> 0, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != P1LobbySession.State.OPENING);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                session.updateNextMode(GameMode.NORMAL, "operator-test", answer::set);
                pumpUntil(callbacks, () -> answer.get() != null);
                assertFalse(answer.get().success());
                assertEquals(P1LobbySession.State.FAILED, session.state());
                assertEquals(GameMode.HARDCORE, session.nextRules().orElseThrow().mode());
                statement.execute("ROLLBACK");
            }
        }
        try (var store = new SqliteRoundStore(database)) {
            assertEquals(GameMode.HARDCORE,
                    RulesConfig.decode(new String(store.loadPendingRules().orElseThrow(), StandardCharsets.UTF_8)).mode());
        }
    }

    @Test
    void dirtySnapshotRestoresRecoveryAndKeepsRawSnapshotWithoutWorldProgression() throws Exception {
        var database = directory.resolve("dirty.sqlite");
        var original = RoundEngine.create(UUID.randomUUID(), 7, 123, RulesConfig.defaults(), () -> 0).committed();
        byte[] payload = RoundSnapshotCodec.encode(original);
        try (var store = new SqliteRoundStore(database)) {
            store.commit(new SnapshotWrite(original.roundId(), -1, 0, payload, UUID.randomUUID(), "TEST_PREPARING"));
        }
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong(100);
        try (var session = new P1LobbySession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != P1LobbySession.State.OPENING);
            var restored = session.snapshot().orElseThrow();
            assertEquals(original.roundId(), restored.roundId());
            assertEquals(RoundPhase.RECOVERY_REQUIRED, restored.phase());
            assertTrue(restored.timingUncertain());
            clock.addAndGet(TimeUnit.DAYS.toNanos(1));
            session.tick();
            assertFalse(session.mutationPending());
            assertEquals(restored, session.snapshot().orElseThrow());
            assertFalse(session.requestJoin(UUID.randomUUID(), "Tester").success());
        }
        try (var store = new SqliteRoundStore(database)) {
            assertArrayEquals(payload, store.latest().orElseThrow().payload());
        }
    }

    @Test
    void closingDuringBootstrapDrainsWithoutNeedingServerCallback() throws Exception {
        var database = directory.resolve("early-stop.sqlite");
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var session = new P1LobbySession(database, () -> 0, callbacks::add);
        session.close();
        assertEquals(P1LobbySession.State.CLOSED, session.state());
        for (var callback : callbacks) callback.run();
        assertEquals(P1LobbySession.State.CLOSED, session.state());
        try (var store = new SqliteRoundStore(database)) {
            RoundSnapshot saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertTrue(saved.cleanShutdown());
            assertEquals(RoundPhase.PREPARING, saved.phase());
        }
    }

    @Test
    void cleanLaterPhaseKeepsIdAndDurationsIsolatedUntilWorldAdaptersExist() throws Exception {
        var database = directory.resolve("future-running.sqlite");
        UUID playerId = UUID.randomUUID();
        var original = new RoundSnapshot(UUID.randomUUID(), 9, 1234, 0, RulesConfig.defaults(),
                RoundPhase.RUNNING, PauseReason.SERVER_STOPPED, 123_000_000, 0,
                Map.of(playerId, new Participant(playerId, "Human", ParticipantRole.HUMAN_ACTIVE,
                        true, false, 42_000_000, 0, 1, false)), true, false, null);
        byte[] payload = RoundSnapshotCodec.encode(original);
        try (var store = new SqliteRoundStore(database)) {
            store.commit(new SnapshotWrite(original.roundId(), -1, 0, payload, UUID.randomUUID(), "TEST_RUNNING"));
        }
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong(TimeUnit.DAYS.toNanos(100));
        try (var session = new P1LobbySession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != P1LobbySession.State.OPENING);
            var restored = session.snapshot().orElseThrow();
            assertEquals(original.roundId(), restored.roundId());
            assertEquals(RoundPhase.RUNNING, restored.phase());
            assertEquals(original.challengeNanos(), restored.challengeNanos());
            assertEquals(42_000_000, restored.participants().get(playerId).humanNanos());
            assertFalse(restored.participants().get(playerId).online());
            clock.addAndGet(TimeUnit.HOURS.toNanos(1));
            session.onOnline(playerId, "Human", true);
            session.tick();
            assertEquals(restored, session.snapshot().orElseThrow());
            assertFalse(session.mutationPending());
            assertFalse(session.requestJoin(playerId, "Human").success());
        }
        try (var store = new SqliteRoundStore(database)) {
            assertArrayEquals(payload, store.latest().orElseThrow().payload());
        }
    }

    @Test
    void normalStopDrainsPendingConfigWithoutWaitingForItsReplyCallback() throws Exception {
        var database = directory.resolve("pending-mode-stop.sqlite");
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var answer = new AtomicReference<P1LobbySession.Reply>();
        var session = new P1LobbySession(database, () -> 0, callbacks::add);
        pumpUntil(callbacks, () -> session.state() != P1LobbySession.State.OPENING);
        session.updateNextMode(GameMode.NORMAL, "operator-test", answer::set);
        assertTrue(session.mutationPending());
        session.close();
        for (var callback : callbacks) callback.run();
        assertNull(answer.get(), "closed session must not send a delayed success reply");
        assertEquals(P1LobbySession.State.CLOSED, session.state());
        try (var store = new SqliteRoundStore(database)) {
            assertEquals(GameMode.NORMAL,
                    RulesConfig.decode(new String(store.loadPendingRules().orElseThrow(), StandardCharsets.UTF_8)).mode());
            assertTrue(RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).cleanShutdown());
        }
    }

    @Test
    void openFailureCannotExposeAUsableRound() throws Exception {
        var parentFile = directory.resolve("regular-file");
        Files.writeString(parentFile, "do not replace");
        var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var session = new P1LobbySession(parentFile.resolve("p1.sqlite"), () -> 0, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != P1LobbySession.State.OPENING);
            assertEquals(P1LobbySession.State.FAILED, session.state());
            assertTrue(session.snapshot().isEmpty());
            assertFalse(session.requestJoin(UUID.randomUUID(), "Tester").success());
            var answer = new AtomicReference<P1LobbySession.Reply>();
            session.updateNextMode(GameMode.NORMAL, "operator-test", answer::set);
            assertFalse(answer.get().success());
        }
        assertEquals("do not replace", Files.readString(parentFile));
    }

    private static void pumpUntil(LinkedBlockingQueue<Runnable> callbacks, BooleanSupplier complete) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!complete.getAsBoolean() && System.nanoTime() < deadline) {
            var task = callbacks.poll(100, TimeUnit.MILLISECONDS);
            if (task != null) task.run();
        }
        assertTrue(complete.getAsBoolean(), "server callback was not completed within timeout");
    }
}
