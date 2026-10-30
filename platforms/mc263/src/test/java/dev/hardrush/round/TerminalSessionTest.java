package dev.hardrush.round;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.*;
import dev.hardrush.core.config.*;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class TerminalSessionTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final UUID dragon = UUID.randomUUID();

    @BeforeEach void canonicalTemporaryDirectory() throws Exception { directory = directory.toRealPath(); }

    @Test void lethalTimeSurvivesQueuedCheckpointAndCommitDelayThenRotatesOnce() throws Exception {
        Path database = directory.resolve("terminal.sqlite");
        var original = seed(database, RoundPhase.RUNNING, null, 0);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong();
        try (var session = open(database, clock, callbacks)) {
            session.onOnline(player, "Human", true);
            pump(callbacks, () -> !session.mutationPending());
            clock.set(TimeUnit.SECONDS.toNanos(5));
            session.tick(); // Pending checkpoint must finish before terminal CAS.
            clock.set(TimeUnit.SECONDS.toNanos(7));
            var lethal = lethal(original.roundId(), clock.get());
            session.captureDragonDeath(lethal, dragon);
            session.normalDeath(player, 1, lethal.batchId());
            session.finishDamageBatch(lethal.roundId(), lethal.batchId());
            assertTrue(session.competitionLocked(), "New attacks are blocked at the lethal callback");
            assertEquals(lethal.batchId(), session.terminalBatchId().orElseThrow());
            clock.set(TimeUnit.SECONDS.toNanos(107));
            pump(callbacks, () -> session.snapshot().orElseThrow().result() != null && !session.mutationPending());
            assertEquals(TimeUnit.SECONDS.toNanos(7), session.view().orElseThrow().challengeNanos());
            assertEquals(player, session.snapshot().orElseThrow().result().winner());
            assertEquals(ParticipantRole.HUMAN_RESPAWNING, session.snapshot().orElseThrow().participants().get(player).role());
            session.captureDragonDeath(lethal(original.roundId(), clock.get()), dragon);
            assertFalse(session.mutationPending(), "Repeated lethal callbacks cannot overwrite terminal result");
            session.tick();
            pump(callbacks, () -> !session.mutationPending());
            assertEquals(RoundPhase.ROTATING, session.snapshot().orElseThrow().phase(), "Commit delay does not restart celebration");
            session.rotationReady(UUID.randomUUID());
            assertFalse(session.mutationPending());
            session.rotationReady(original.roundId()); // Represents verified player/replay/world release.
            session.rotationReady(original.roundId());
            pump(callbacks, () -> !session.mutationPending());
            var next = session.snapshot().orElseThrow();
            assertEquals(2, next.sequence());
            assertNotEquals(original.roundId(), next.roundId());
            assertEquals(RoundPhase.PREPARING, next.phase());
            assertTrue(next.participants().isEmpty());
            assertNull(next.result());
            assertEquals(0, next.challengeNanos());
            session.rotationReady(original.roundId());
            assertEquals(next.roundId(), session.snapshot().orElseThrow().roundId());
        }
        try (var store = new SqliteRoundStore(database)) {
            assertTrue(store.terminal(original.roundId()).isPresent());
            assertEquals(1, store.evidence(original.roundId(), "COMBAT", 10).size());
            var old = RoundSnapshotCodec.decode(store.load(original.roundId()).orElseThrow().payload());
            assertEquals(TimeUnit.SECONDS.toNanos(7), old.challengeNanos());
            assertEquals(player, old.result().winner());
            assertEquals(2, RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).sequence());
        }
    }

    @Test void terminalWriteFailureCannotPublishWinnerOrCreateNextRound() throws Exception {
        Path database = directory.resolve("terminal-locked.sqlite");
        var original = seed(database, RoundPhase.RUNNING, null, 0);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong();
        try (var session = open(database, clock, callbacks);
             var lock = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = lock.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            var death = lethal(original.roundId(), clock.get());
            session.captureDragonDeath(death, dragon);
            session.finishDamageBatch(death.roundId(), death.batchId());
            pump(callbacks, () -> session.state() == RoundSession.State.FAILED);
            assertNull(session.snapshot().orElseThrow().result());
            assertTrue(session.competitionLocked());
            session.rotationReady(original.roundId());
            assertEquals(original.roundId(), session.snapshot().orElseThrow().roundId());
            sql.execute("ROLLBACK");
        }
        try (var store = new SqliteRoundStore(database)) {
            assertTrue(store.terminal(original.roundId()).isEmpty());
            assertTrue(store.evidence(original.roundId(), "COMBAT", 10).isEmpty());
            assertFalse(RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).cleanShutdown());
        }
    }

    @Test void cleanCelebrationRestartRotatesInsteadOfResuming() throws Exception {
        Path database = directory.resolve("celebration.sqlite");
        var original = seed(database, RoundPhase.CELEBRATING,
                new RoundResult(RoundResult.Kind.COMPLETED, player, "fixture"), TimeUnit.SECONDS.toNanos(2));
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong(TimeUnit.DAYS.toNanos(5));
        try (var session = open(database, clock, callbacks)) {
            var restored = session.snapshot().orElseThrow();
            assertTrue(session.competitionLocked());
            assertEquals(original.roundId(), restored.roundId());
            assertEquals(RoundPhase.ROTATING, restored.phase());
            assertEquals(0, session.view().orElseThrow().remainingPhaseNanos());
            assertEquals(original.result(), restored.result());
            assertEquals(123_000_000, restored.challengeNanos());
        }
    }

    @Test void evidenceAfterCommittedResultIsNeverStored() throws Exception {
        Path database = directory.resolve("evidence-closed.sqlite");
        var original = seed(database, RoundPhase.RUNNING, null, 0);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong();
        var running = new UUID[1];
        var settled = new UUID[1];
        try (var session = open(database, clock, callbacks)) {
            var before = lethal(original.roundId(), 0);
            running[0] = before.eventId();
            session.recordEvidence(new EvidenceWrite(original.roundId(), before.eventId(), "COMBAT", CombatEventCodec.encode(before)));
            session.onOnline(player, "Human", true);
            pump(callbacks, () -> !session.mutationPending());
            clock.set(TimeUnit.SECONDS.toNanos(3));
            session.captureDragonDeath(lethal(original.roundId(), clock.get()), dragon);
            session.finishDamageBatch(original.roundId(), session.terminalBatchId().orElseThrow());
            pump(callbacks, () -> session.snapshot().orElseThrow().result() != null && !session.mutationPending());
            var after = lethal(original.roundId(), clock.get());
            settled[0] = after.eventId();
            session.recordEvidence(new EvidenceWrite(original.roundId(), after.eventId(), "COMBAT", CombatEventCodec.encode(after)));
            pump(callbacks, () -> !session.mutationPending());
        }
        for (var callback : callbacks) callback.run();
        try (var store = new SqliteRoundStore(database)) {
            var stored = store.evidence(original.roundId(), "COMBAT", 10).stream().map(EvidenceWrite::eventId).toList();
            assertTrue(stored.contains(running[0]), "Evidence before the result is stored");
            assertFalse(stored.contains(settled[0]), "Evidence after the committed result is not stored");
        }
    }

    @Test void normalStopDrainsAcceptedLethalEventAndItsAtomicEvidence() throws Exception {
        Path database = directory.resolve("terminal-stop.sqlite");
        var original = seed(database, RoundPhase.RUNNING, null, 0);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong();
        var session = open(database, clock, callbacks);
        session.onOnline(player, "Human", true);
        pump(callbacks, () -> !session.mutationPending());
        clock.set(TimeUnit.SECONDS.toNanos(3));
        session.captureDragonDeath(lethal(original.roundId(), clock.get()), dragon);
        clock.set(TimeUnit.SECONDS.toNanos(4));
        session.prepareStop();
        clock.set(TimeUnit.SECONDS.toNanos(100)); // Platform IO drain happens after the fixed shutdown boundary.
        session.close();
        for (var callback : callbacks) callback.run();
        try (var store = new SqliteRoundStore(database)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertTrue(saved.cleanShutdown());
            assertEquals(TimeUnit.SECONDS.toNanos(3), saved.challengeNanos());
            assertEquals(player, saved.result().winner());
            assertEquals(TimeUnit.SECONDS.toNanos(1), saved.remainingPhaseNanos());
            assertTrue(store.terminal(original.roundId()).isPresent());
        }
    }

    @Test void onlineEventDuringCapturedBatchCannotMoveFinishBehindItsTimestamp() throws Exception {
        Path database = directory.resolve("terminal-online-order.sqlite");
        var original = seed(database, RoundPhase.RUNNING, null, 0);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong();
        try (var session = open(database, clock, callbacks)) {
            session.onOnline(player, "Human", true);
            pump(callbacks, () -> !session.mutationPending());
            clock.set(100);
            var death = lethal(original.roundId(), clock.get());
            session.captureDragonDeath(death, dragon);
            clock.set(101);
            session.onOnline(player, "Human", false);
            session.tick();
            assertFalse(session.mutationPending(), "Session changes wait until the native terminal batch closes");
            session.finishDamageBatch(death.roundId(), death.batchId());
            pump(callbacks, () -> !session.mutationPending());
            assertEquals(RoundSession.State.OPEN, session.state());
            assertEquals(100, session.snapshot().orElseThrow().challengeNanos());
            session.tick();
            pump(callbacks, () -> !session.mutationPending());
            assertFalse(session.snapshot().orElseThrow().participants().get(player).online());
        }
    }

    @Test void stopBoundaryExcludesPlatformDrainAndFailurePreventsCleanMarker() throws Exception {
        Path database = directory.resolve("platform-stop-failure.sqlite");
        var original = seed(database, RoundPhase.RUNNING, null, 0);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var clock = new AtomicLong();
        var session = open(database, clock, callbacks);
        session.onOnline(player, "Human", true);
        pump(callbacks, () -> !session.mutationPending());
        clock.set(TimeUnit.SECONDS.toNanos(3));
        var death = lethal(original.roundId(), clock.get());
        session.captureDragonDeath(death, dragon);
        session.normalDeath(player, 1, death.batchId());
        session.prepareStop();
        clock.set(TimeUnit.SECONDS.toNanos(100));
        session.adapterFailure("world save failed", new IllegalStateException("fixture"));
        session.close();
        try (var store = new SqliteRoundStore(database)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertFalse(saved.cleanShutdown(), "Platform failure must not create a clean recovery marker");
            assertEquals(TimeUnit.SECONDS.toNanos(3), saved.challengeNanos());
            assertEquals(player, saved.result().winner());
            assertEquals(ParticipantRole.HUMAN_RESPAWNING, saved.participants().get(player).role());
            assertTrue(store.terminal(original.roundId()).isPresent(), "Accepted lethal evidence still drains atomically");
        }
    }

    private RoundSnapshot seed(Path database, RoundPhase phase, RoundResult result, long remaining) throws Exception {
        var defaults = RulesConfig.defaults().withMode(GameMode.NORMAL);
        var rules = new RulesConfig(defaults.schemaVersion(), defaults.mode(), defaults.minecraftVersion(),
                defaults.difficulty(), defaults.generationRule(), defaults.combatValues(), defaults.protectionRadius(),
                1, 2_000, 1_000);
        var snapshot = new RoundSnapshot(UUID.randomUUID(), 1, 42, 0, rules, phase, PauseReason.SERVER_STOPPED,
                result == null ? 0 : 123_000_000, remaining,
                Map.of(player, new Participant(player, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 0, 0, 1, false)),
                true, false, result);
        try (var store = new SqliteRoundStore(database)) {
            store.savePendingRules(rules.encode().getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), "fixture");
            store.commit(new SnapshotWrite(snapshot.roundId(), -1, 0, RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "INITIAL"));
        }
        return snapshot;
    }

    private CombatEvent lethal(UUID roundId, long time) {
        var actor = new CombatActor(player, ParticipantRole.HUMAN_ACTIVE, 1, 0);
        var origin = new CombatOrigin(roundId, UUID.randomUUID(), actor, AttackKind.ARROW, Optional.of(UUID.randomUUID()), List.of());
        return new CombatEvent(roundId, UUID.randomUUID(), UUID.randomUUID(), 1, dragon, Optional.of(origin),
                CombatEvent.Attribution.HUMAN, "minecraft:arrow", origin.directEntityId(), Optional.of(player), 1, true, time);
    }

    private static RoundSession open(Path database, AtomicLong clock, LinkedBlockingQueue<Runnable> callbacks) throws Exception {
        var session = new RoundSession(database, clock::get, callbacks::add);
        pump(callbacks, () -> session.state() == RoundSession.State.OPEN);
        session.adaptersReady(session.snapshot().orElseThrow().roundId());
        pump(callbacks, () -> !session.mutationPending());
        return session;
    }

    private static void pump(LinkedBlockingQueue<Runnable> callbacks, BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            var task = callbacks.poll(50, TimeUnit.MILLISECONDS);
            if (task != null) task.run();
        }
        assertTrue(done.getAsBoolean(), "Durable callback timed out");
    }
}
