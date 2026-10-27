package dev.hardrush.round;

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
final class RoundSessionTest {
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
        try (var session = new RoundSession(database, clock::get, callbacks::add)) {
            assertEquals(RoundSession.State.OPENING, session.state());
            assertTrue(session.snapshot().isEmpty());
            pumpUntil(callbacks, () -> session.state() != RoundSession.State.OPENING);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
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
        try (var reopened = new RoundSession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> reopened.state() != RoundSession.State.OPENING);
            var restored = reopened.snapshot().orElseThrow();
            assertEquals(RoundSession.State.OPEN, reopened.state(), reopened.problem());
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
        var answer = new AtomicReference<RoundSession.Reply>();
        try (var session = new RoundSession(database, () -> 0, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != RoundSession.State.OPENING);
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
        var answer = new AtomicReference<RoundSession.Reply>();
        try (var session = new RoundSession(database, () -> 0, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != RoundSession.State.OPENING);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                session.updateNextMode(GameMode.NORMAL, "operator-test", answer::set);
                pumpUntil(callbacks, () -> answer.get() != null);
                assertFalse(answer.get().success());
                assertEquals(RoundSession.State.FAILED, session.state());
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
        try (var session = new RoundSession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != RoundSession.State.OPENING);
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
        var session = new RoundSession(database, () -> 0, callbacks::add);
        session.close();
        assertEquals(RoundSession.State.CLOSED, session.state());
        for (var callback : callbacks) callback.run();
        assertEquals(RoundSession.State.CLOSED, session.state());
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
        try (var session = new RoundSession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != RoundSession.State.OPENING);
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
        var answer = new AtomicReference<RoundSession.Reply>();
        var session = new RoundSession(database, () -> 0, callbacks::add);
        pumpUntil(callbacks, () -> session.state() != RoundSession.State.OPENING);
        session.updateNextMode(GameMode.NORMAL, "operator-test", answer::set);
        assertTrue(session.mutationPending());
        session.close();
        for (var callback : callbacks) callback.run();
        assertNull(answer.get(), "closed session must not send a delayed success reply");
        assertEquals(RoundSession.State.CLOSED, session.state());
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
        try (var session = new RoundSession(parentFile.resolve("p1.sqlite"), () -> 0, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() != RoundSession.State.OPENING);
            assertEquals(RoundSession.State.FAILED, session.state());
            assertTrue(session.snapshot().isEmpty());
            assertFalse(session.requestJoin(UUID.randomUUID(), "Tester").success());
            var answer = new AtomicReference<RoundSession.Reply>();
            session.updateNextMode(GameMode.NORMAL, "operator-test", answer::set);
            assertFalse(answer.get().success());
        }
        assertEquals("do not replace", Files.readString(parentFile));
    }

    @Test
    void verifiedAdaptersUnlockDurableJoinAndCountdownThenNormalDeathPausesAtCapturedEvent() throws Exception {
        Path database = directory.resolve("playable.sqlite");
        try (var store = new SqliteRoundStore(database)) {
            store.savePendingRules(RulesConfig.defaults().withMode(GameMode.NORMAL).encode().getBytes(StandardCharsets.UTF_8),
                    UUID.randomUUID(), "test-bootstrap");
        }
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong(0);
        var answer = new AtomicReference<RoundSession.Reply>();
        UUID player = UUID.randomUUID();
        try (var session = new RoundSession(database, clock::get, callbacks::add)) {
            session.onCommitted((before, after) -> {
                var participant = after.participants().get(player);
                var previous = before.participants().get(player);
                if (participant != null && participant.role() == ParticipantRole.HUMAN_ACTIVE && !participant.online()
                        && (previous == null || previous.role() == ParticipantRole.LOBBY || previous.role() == ParticipantRole.HUMAN_RESPAWNING)) {
                    session.onOnline(player, "Human", true); // The verified adapter's actual play-unlock boundary.
                }
            });
            pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
            session.adaptersReady(session.snapshot().orElseThrow().roundId());
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(RoundPhase.WAITING, session.snapshot().orElseThrow().phase());
            session.onOnline(player, "Human", true);
            session.requestJoin(player, "Human", answer::set);
            assertNull(answer.get());
            assertTrue(session.snapshot().orElseThrow().participants().isEmpty());
            pumpUntil(callbacks, () -> answer.get() != null);
            assertTrue(answer.get().success(), answer.get().message());
            answer.set(null);
            session.requestReady(player, true, answer::set);
            pumpUntil(callbacks, () -> answer.get() != null);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            session.tick();
            clock.addAndGet(TimeUnit.SECONDS.toNanos(10)); // Database delay before the adapter opens actual play.
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(RoundPhase.RUNNING, session.snapshot().orElseThrow().phase());
            assertEquals(0, session.view().orElseThrow().challengeNanos(), "Pre-admission SQLite delay is excluded");
            clock.addAndGet(TimeUnit.SECONDS.toNanos(7));
            session.tick(); // Keep a durable checkpoint pending while the death arrives.
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            session.normalDeath(player, 1);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(100)); // Delayed IO callback must not extend the death time.
            pumpUntil(callbacks, () -> session.snapshot().orElseThrow().participants().get(player).role() == ParticipantRole.HUMAN_RESPAWNING);
            assertEquals(TimeUnit.SECONDS.toNanos(9), session.snapshot().orElseThrow().challengeNanos());
            assertEquals(TimeUnit.SECONDS.toNanos(9), session.view().orElseThrow().challengeNanos());
            session.normalRespawn(player, 1);
            pumpUntil(callbacks, () -> !session.mutationPending());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            session.onOnline(player, "Human", false);
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(TimeUnit.SECONDS.toNanos(12), session.view().orElseThrow().challengeNanos());
            assertEquals(2, session.snapshot().orElseThrow().participants().get(player).lifeSequence());
        }
        clock.addAndGet(TimeUnit.DAYS.toNanos(3));
        try (var session = new RoundSession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
            session.adaptersReady(session.snapshot().orElseThrow().roundId());
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(RoundPhase.RUNNING, session.snapshot().orElseThrow().phase());
            assertEquals(TimeUnit.SECONDS.toNanos(12), session.view().orElseThrow().challengeNanos());
            assertFalse(session.snapshot().orElseThrow().timingUncertain());
        }
    }

    @Test
    void hardcoreDeathAndProtectionPersistAcrossNormalStopAndRejoin() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var reply = new AtomicReference<RoundSession.Reply>();
        var clock = new AtomicLong();
        UUID player = UUID.randomUUID();
        var database = directory.resolve("hardcore-identity.sqlite");
        try (var session = new RoundSession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
            session.adaptersReady(session.snapshot().orElseThrow().roundId());
            pumpUntil(callbacks, () -> !session.mutationPending());
            session.onOnline(player, "Human", true);
            session.requestJoin(player, "Human", reply::set);
            pumpUntil(callbacks, () -> reply.get() != null);
            reply.set(null);
            session.requestReady(player, true, reply::set);
            pumpUntil(callbacks, () -> reply.get() != null);
            assertTrue(reply.get().success(), reply.get().message());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            session.tick();
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(RoundPhase.RUNNING, session.snapshot().orElseThrow().phase());
            session.onOnline(player, "Human", true);
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertTrue(session.leaveProtection(session.snapshot().orElseThrow().roundId(), player, 1));
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertTrue(session.snapshot().orElseThrow().participants().get(player).hasLeftProtection());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(7));
            session.humanDeath(player, 1, null);
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(ParticipantRole.MONSTER_LOBBY, session.snapshot().orElseThrow().participants().get(player).role());
            assertEquals(TimeUnit.SECONDS.toNanos(7), session.view().orElseThrow().challengeNanos());
            assertTrue(session.leaveProtection(session.snapshot().orElseThrow().roundId(), player, 1));
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(RoundSession.State.OPEN, session.state(), "A queued movement from the dead life is harmless");
            reply.set(null);
            session.requestJoin(player, "Human", reply::set);
            pumpUntil(callbacks, () -> reply.get() != null);
            assertFalse(reply.get().success());
        }
        clock.addAndGet(TimeUnit.DAYS.toNanos(3));
        try (var session = new RoundSession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
            session.adaptersReady(session.snapshot().orElseThrow().roundId());
            pumpUntil(callbacks, () -> !session.mutationPending());
            session.onOnline(player, "Renamed", true);
            pumpUntil(callbacks, () -> !session.mutationPending());
            var dead = session.snapshot().orElseThrow().participants().get(player);
            assertEquals(ParticipantRole.MONSTER_LOBBY, dead.role());
            assertEquals(1, dead.lifeSequence());
            assertTrue(dead.hasLeftProtection());
            assertEquals(TimeUnit.SECONDS.toNanos(7), dead.humanNanos());
            assertEquals(TimeUnit.SECONDS.toNanos(7), session.view().orElseThrow().challengeNanos());
        }
    }

    @Test
    void lateOriginalNeedsItsOwnStandingProofAfterFirstOriginalAlreadyResumed() throws Exception {
        var db = directory.resolve("late-original.sqlite"); UUID a = UUID.randomUUID(), b = UUID.randomUUID(), round = UUID.randomUUID();
        var rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
        var participants = Map.of(a, new Participant(a, "A", ParticipantRole.LOBBY, true, true, 0, -1, 0, false),
                b, new Participant(b, "B", ParticipantRole.LOBBY, true, true, 0, -1, 0, false));
        var saved = new RoundSnapshot(round, 1, 8, 0, rules, RoundPhase.COUNTDOWN, PauseReason.SERVER_STOPPED,
                0, TimeUnit.SECONDS.toNanos(23), participants, true, false, null);
        try(var store = new SqliteRoundStore(db)) { store.commit(new SnapshotWrite(round,-1,0,RoundSnapshotCodec.encode(saved),UUID.randomUUID(),"fixture")); }
        var callbacks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        var valid = new java.util.HashSet<UUID>(); var reply = new AtomicReference<RoundSession.Reply>();
        try(var session = new RoundSession(db, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
            session.assemblyGuard(valid::contains); session.adaptersReady(round); pumpUntil(callbacks, () -> !session.mutationPending());
            valid.add(a); session.onOnline(a,"A",true); session.restoreReady(round,a,"A",true,reply::set); pumpUntil(callbacks, () -> reply.get()!=null);
            assertFalse(session.recoveringCountdown()); assertTrue(session.originalReady(round,b));
            session.onOnline(b,"B",true); clock.addAndGet(TimeUnit.SECONDS.toNanos(4)); session.tick(); pumpUntil(callbacks, () -> !session.mutationPending());
            assertFalse(session.snapshot().orElseThrow().participants().get(b).online(), "Late original online is not a standing proof");
            assertEquals(19000, session.view().orElseThrow().remainingPhaseMillis());
            reply.set(null); session.restoreReady(round,b,"B",true,reply::set); assertFalse(reply.get().success());
            valid.add(b); reply.set(null); session.restoreReady(round,b,"B",true,reply::set); pumpUntil(callbacks, () -> reply.get()!=null);
            assertTrue(reply.get().success()); assertTrue(session.snapshot().orElseThrow().participants().get(b).online());
            assertEquals(19000,session.view().orElseThrow().remainingPhaseMillis()); assertFalse(session.originalReady(round,b));
        }
    }

    @Test
    void lateAssemblyCommitIsCancelledBeforeStartAndOldRoundFailsWithoutIsolation() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>(); var clock = new AtomicLong();
        var inside = new java.util.concurrent.atomic.AtomicBoolean(true); UUID id = UUID.randomUUID();
        var reply = new AtomicReference<RoundSession.Reply>();
        try (var session = new RoundSession(directory.resolve("assembly.sqlite"), clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
            var round = session.snapshot().orElseThrow().roundId(); session.adaptersReady(round);
            pumpUntil(callbacks, () -> !session.mutationPending()); session.assemblyGuard(p -> inside.get());
            session.onOnline(id, "A", true);
            session.requestAssembly(round, id, "A", true, reply::set);
            assertTrue(session.snapshot().orElseThrow().participants().isEmpty()); assertNull(reply.get());
            inside.set(false); clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
            pumpUntil(callbacks, () -> reply.get() != null);
            session.tick(); pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(RoundPhase.WAITING, session.snapshot().orElseThrow().phase());
            assertEquals(0, session.snapshot().orElseThrow().participants().get(id).lifeSequence());
            inside.set(true); reply.set(null); session.requestAssembly(UUID.randomUUID(), id, "A", true, reply::set);
            assertFalse(reply.get().success()); assertEquals(RoundSession.State.OPEN, session.state());
            reply.set(null); session.requestAssembly(round, id, "A", true, reply::set); pumpUntil(callbacks, () -> reply.get() != null);
            assertEquals(30_000, session.snapshot().orElseThrow().remainingPhaseMillis());
            inside.set(false); session.prepareStop();
        }
        try (var store = new SqliteRoundStore(directory.resolve("assembly.sqlite"))) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertEquals(RoundPhase.WAITING, saved.phase()); assertFalse(saved.participants().get(id).ready());
        }
    }

    @Test
    void cleanCountdownWaitsForProfilesAcrossCheckpointsAndRepeatedNormalStops() throws Exception {
        Path database = directory.resolve("countdown-repeat-stop.sqlite");
        UUID ready = UUID.randomUUID(), unready = UUID.randomUUID();
        var initial = seedCleanCountdown(database, ready, unready);
        for (int restart = 0; restart < 2; restart++) {
            var clock = new AtomicLong(TimeUnit.DAYS.toNanos(10 + restart));
            var callbacks = new LinkedBlockingQueue<Runnable>();
            try (var session = new RoundSession(database, clock::get, callbacks::add)) {
                pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
                session.adaptersReady(initial.roundId());
                pumpUntil(callbacks, () -> !session.mutationPending());
                assertFalse(session.snapshot().orElseThrow().cleanShutdown(), "Ready worlds mark the live process dirty");
                for (int checkpoint = 0; checkpoint < 3; checkpoint++) {
                    clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
                    session.tick();
                    pumpUntil(callbacks, () -> !session.mutationPending());
                    var held = session.snapshot().orElseThrow();
                    assertEquals(RoundPhase.COUNTDOWN, held.phase(), "Startup offline sessions are not deliberate disconnects");
                    assertEquals(initial.remainingPhaseNanos(), held.remainingPhaseNanos());
                    assertTrue(held.participants().get(ready).ready());
                    assertFalse(held.participants().get(ready).online());
                    assertEquals(0, held.challengeNanos());
                }
                session.prepareStop();
                clock.addAndGet(TimeUnit.SECONDS.toNanos(100));
            }
            try (var store = new SqliteRoundStore(database)) {
                var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
                assertTrue(saved.cleanShutdown());
                assertEquals(initial.roundId(), saved.roundId());
                assertEquals(RoundPhase.COUNTDOWN, saved.phase());
                assertEquals(initial.remainingPhaseNanos(), saved.remainingPhaseNanos(), "Repeated normal stop cannot consume the held remainder");
                assertTrue(saved.participants().get(ready).ready());
            }
        }
    }

    @Test
    void recoveredCountdownNeedsSavedReadyUuidAndThenUsesOrdinaryCancelRules() throws Exception {
        Path database = directory.resolve("countdown-resume.sqlite");
        UUID ready = UUID.randomUUID(), unready = UUID.randomUUID(), newcomer = UUID.randomUUID();
        var initial = seedCleanCountdown(database, ready, unready);
        var clock = new AtomicLong(0);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var reply = new AtomicReference<RoundSession.Reply>();
        try (var session = new RoundSession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
            session.adaptersReady(initial.roundId());
            pumpUntil(callbacks, () -> !session.mutationPending());
            session.onOnline(unready, "UnreadyReconnected", true);
            session.onOnline(newcomer, "Newcomer", true);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
            session.tick();
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(RoundPhase.COUNTDOWN, session.snapshot().orElseThrow().phase());
            assertEquals(initial.remainingPhaseNanos(), session.view().orElseThrow().remainingPhaseNanos());
            session.requestJoin(newcomer, "Newcomer", reply::set);
            assertFalse(reply.get().success(), "New join cannot substitute for the saved ready UUID during recovery");
            session.onOnline(unready, "UnreadyReconnected", false);
            session.onOnline(newcomer, "Newcomer", false);
            session.onOnline(ready, "ReadyReconnected", true);
            assertTrue(session.recoveringCountdown(), "Online alone proves no safe circle position");
            session.restoreReady(initial.roundId(), ready, "ReadyReconnected", true, reply::set);
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertTrue(session.snapshot().orElseThrow().participants().get(ready).online());
            assertEquals(initial.remainingPhaseNanos(), session.view().orElseThrow().remainingPhaseNanos());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(4));
            session.tick();
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(initial.remainingPhaseNanos() - TimeUnit.SECONDS.toNanos(4), session.snapshot().orElseThrow().remainingPhaseNanos());
            reply.set(null);
            session.requestReady(ready, false, reply::set);
            pumpUntil(callbacks, () -> reply.get() != null);
            assertTrue(reply.get().success());
            assertEquals(RoundPhase.WAITING, session.snapshot().orElseThrow().phase(), "Explicit unready still cancels after recovery");
            reply.set(null);
            session.requestReady(ready, true, reply::set);
            pumpUntil(callbacks, () -> reply.get() != null);
            assertTrue(reply.get().success());
            assertEquals(TimeUnit.SECONDS.toNanos(30), session.snapshot().orElseThrow().remainingPhaseNanos(), "A fresh countdown uses the full rule duration");
            session.onOnline(ready, "ReadyReconnected", false);
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(RoundPhase.WAITING, session.snapshot().orElseThrow().phase(), "Last ready disconnect still cancels a live countdown");
            assertFalse(session.snapshot().orElseThrow().participants().get(ready).ready());
        }
    }

    @Test
    void readyProfileOnlineBeforeWorldGateResumesOnlyAfterWorldVerification() throws Exception {
        Path database = directory.resolve("countdown-early-profile.sqlite");
        UUID ready = UUID.randomUUID(), unready = UUID.randomUUID();
        var initial = seedCleanCountdown(database, ready, unready);
        var clock = new AtomicLong(0);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var session = new RoundSession(database, clock::get, callbacks::add)) {
            pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
            session.onOnline(ready, "ReadyBeforeWorlds", true);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
            session.tick();
            assertFalse(session.snapshot().orElseThrow().participants().get(ready).online());
            assertEquals(initial.remainingPhaseNanos(), session.view().orElseThrow().remainingPhaseNanos());
            session.adaptersReady(initial.roundId());
            pumpUntil(callbacks, () -> !session.mutationPending());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
            session.tick();
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertFalse(session.snapshot().orElseThrow().participants().get(ready).online(), "World readiness cannot prove native standing");
            var restored = new AtomicReference<RoundSession.Reply>();
            session.restoreReady(initial.roundId(), ready, "ReadyBeforeWorlds", true, restored::set);
            pumpUntil(callbacks, () -> restored.get() != null);
            assertTrue(session.snapshot().orElseThrow().participants().get(ready).online());
            assertEquals(initial.remainingPhaseNanos(), session.view().orElseThrow().remainingPhaseNanos(), "Pre-world and held-profile time is excluded");
            clock.addAndGet(initial.remainingPhaseNanos());
            session.tick();
            pumpUntil(callbacks, () -> !session.mutationPending());
            assertEquals(RoundPhase.RUNNING, session.snapshot().orElseThrow().phase());
            assertEquals(0, session.view().orElseThrow().challengeNanos());
            assertFalse(session.snapshot().orElseThrow().participants().get(ready).online(), "Actual human admission remains separately gated");
        }
    }

    private static RoundSnapshot seedCleanCountdown(Path database, UUID ready, UUID unready) throws Exception {
        var rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
        var snapshot = new RoundSnapshot(UUID.randomUUID(), 1, 123, 0, rules, RoundPhase.COUNTDOWN,
                PauseReason.SERVER_STOPPED, 0, TimeUnit.SECONDS.toNanos(17),
                Map.of(ready, new Participant(ready, "Ready", ParticipantRole.LOBBY, true, true, 0, -1, 0, false),
                        unready, new Participant(unready, "Unready", ParticipantRole.LOBBY, true, false, 0, -1, 0, false)),
                true, false, null);
        try (var store = new SqliteRoundStore(database)) {
            store.savePendingRules(rules.encode().getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), "countdown-fixture");
            assertEquals(dev.hardrush.persistence.CommitResult.APPLIED, store.commit(new SnapshotWrite(snapshot.roundId(), -1, 0,
                    RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "INITIAL")));
        }
        return snapshot;
    }

    @Test
    void stopCapturesTimeBeforeDrainingBlockedSqliteWrites() throws Exception {
        Path database = directory.resolve("stop-boundary.sqlite");
        UUID player = UUID.randomUUID();
        var rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
        var original = new RoundSnapshot(UUID.randomUUID(), 1, 123, 0, rules, RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 0, 0,
                Map.of(player, new Participant(player, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 0, 0, 1, false)),
                true, false, null);
        try (var store = new SqliteRoundStore(database)) {
            store.savePendingRules(rules.encode().getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), "fixture");
            store.commit(new SnapshotWrite(original.roundId(), -1, 0, RoundSnapshotCodec.encode(original), UUID.randomUUID(), "INITIAL"));
        }
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong(0);
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var stopCaptured = new java.util.concurrent.CountDownLatch(1);
        var session = new RoundSession(database, () -> {
            long now = clock.get();
            if (armed.get()) stopCaptured.countDown();
            return now;
        }, callbacks::add);
        pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
        session.adaptersReady(original.roundId());
        pumpUntil(callbacks, () -> !session.mutationPending());
        session.onOnline(player, "Human", true);
        pumpUntil(callbacks, () -> !session.mutationPending());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
        try (var lock = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = lock.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            session.updateNextMode(GameMode.HARDCORE, "test-operator", reply -> {});
            var release = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    assertTrue(stopCaptured.await(5, TimeUnit.SECONDS));
                    clock.addAndGet(TimeUnit.SECONDS.toNanos(100));
                    sql.execute("ROLLBACK");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            armed.set(true);
            session.close();
            release.get();
        }
        try (var store = new SqliteRoundStore(database)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertTrue(saved.cleanShutdown());
            assertEquals(TimeUnit.SECONDS.toNanos(5), saved.challengeNanos(), "Stop-drain IO time is excluded");
            assertEquals(TimeUnit.SECONDS.toNanos(5), saved.participants().get(player).humanNanos());
        }
    }

    @Test
    void evidenceWriteFailureDuringStopCannotLeaveACleanRecoveryMarker() throws Exception {
        Path database = directory.resolve("stop-evidence-failure.sqlite");
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var session = new RoundSession(database, () -> 0, callbacks::add);
        pumpUntil(callbacks, () -> session.state() == RoundSession.State.OPEN);
        UUID collision;
        try (var raw = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = raw.createStatement();
             var rows = sql.executeQuery("SELECT event_id FROM store_events WHERE scope_type='ROUND' ORDER BY sequence LIMIT 1")) {
            assertTrue(rows.next()); collision = UUID.fromString(rows.getString(1));
        }
        session.recordEvidence(new dev.hardrush.persistence.EvidenceWrite(session.snapshot().orElseThrow().roundId(), collision, "COMBAT", new byte[]{1}));
        assertThrows(IllegalStateException.class, session::close);
        try (var store = new SqliteRoundStore(database)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertFalse(saved.cleanShutdown(), "Missing causal evidence must keep crash recovery mandatory");
        }
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
