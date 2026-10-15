package dev.hardrush.p1;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.*;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P2DeathBatchSessionTest {
    @TempDir Path temp;
    final UUID round = UUID.randomUUID(), hunter = UUID.randomUUID(), victim = UUID.randomUUID();
    final AtomicLong clock = new AtomicLong();
    final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    @Test void lethalEvidenceAndSelfDeathCommitBeforeRescueAndDuplicateRevivalCannotConsumeAgain() throws Exception {
        Path path = seed();
        try (var session = open(path)) {
            UUID batch = UUID.randomUUID();
            session.captureDeath(round, victim, 1, 0, batch);
            session.captureDeath(round, hunter, 1, 1, batch);
            assertFalse(session.mutationPending(), "Native batch must close before any partial identity is published");
            session.captureLethalEvidence(lethal(batch));
            session.finishDamageBatch(round, batch);
            assertEquals(ParticipantRole.HUMAN_ACTIVE, session.snapshot().orElseThrow().participants().get(victim).role());
            session.captureDeath(round, victim, 1, 0, batch);
            session.captureLethalEvidence(lethal(batch));
            session.finishDamageBatch(round, batch);
            pump(() -> !session.mutationPending());
            assertEquals(P1LobbySession.State.OPEN, session.state(), session.problem());
            var saved = session.snapshot().orElseThrow();
            assertEquals(RoundPhase.RESCUE, saved.phase());
            assertTrue(saved.participants().get(hunter).revivalEntitled());
            assertEquals(ParticipantRole.MONSTER_LOBBY, saved.participants().get(hunter).role());
            long version = saved.version();
            session.finishDamageBatch(round, batch);
            assertEquals(version, session.snapshot().orElseThrow().version());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(59));
            var first = new AtomicReference<P1LobbySession.Reply>();
            var duplicate = new AtomicReference<P1LobbySession.Reply>();
            session.requestRevival(round, hunter, 1, 1, first::set);
            session.requestRevival(round, hunter, 1, 1, duplicate::set);
            assertFalse(duplicate.get().success());
            // Accepted before the deadline: the SQLite worker's latency cannot invalidate admission.
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            assertFalse(session.competitionLocked(), "A valid accepted revival reserves continuation across its IO delay");
            session.tick();
            pump(() -> first.get() != null);
            assertTrue(first.get().success(), first.get().message());
            saved = session.snapshot().orElseThrow();
            assertEquals(RoundPhase.RUNNING, saved.phase());
            assertEquals(2, saved.participants().get(hunter).lifeSequence());
            assertFalse(saved.participants().get(hunter).revivalEntitled());
            assertFalse(saved.participants().get(hunter).online(), "Human clock waits for the profile adapter");
        }
        try (var store = new SqliteRoundStore(path)) {
            var snapshot = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertEquals(2, snapshot.participants().get(hunter).lifeSequence());
            assertEquals(1, store.participantRecord(round, hunter).orElseThrow().revivalCount());
            assertEquals(1, store.participantRecord(round, victim).orElseThrow().deathCount());
        }
    }

    @Test void rescueDeadlineSurvivesNormalReopenAndRejectsLateAdmission() throws Exception {
        Path path = seed();
        try (var session = open(path)) {
            UUID batch = UUID.randomUUID();
            session.captureDeath(round, victim, 1, 0, batch);
            session.captureLethalEvidence(lethal(batch));
            session.finishDamageBatch(round, batch);
            pump(() -> !session.mutationPending());
            // Return remains possible while rescue runs.
            var reply = new AtomicReference<P1LobbySession.Reply>();
            session.requestMonsterReturn(round, hunter, 1, 1, reply::set);
            pump(() -> reply.get() != null);
            assertTrue(reply.get().success());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(20));
        }
        try (var session = open(path)) {
            assertEquals(40_000, session.view().orElseThrow().remainingPhaseMillis());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(39));
            var selection = new AtomicReference<P1LobbySession.Reply>();
            session.requestMonsterSelection(round, hunter, 1, 1, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, selection::set);
            assertTrue(session.mutationPending());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            assertTrue(session.competitionLocked(), "An unrelated pending write must not extend the rescue deadline");
            pump(() -> selection.get() != null);
            var reply = new AtomicReference<P1LobbySession.Reply>();
            session.requestRevival(round, hunter, 1, 1, reply::set);
            assertFalse(reply.get().success());
            session.tick();
            pump(() -> !session.mutationPending());
            assertEquals(RoundResult.Kind.FAILED, session.snapshot().orElseThrow().result().kind());
        }
    }

    CombatEvent lethal(UUID batch) {
        var origin = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(hunter, ParticipantRole.MONSTER_DEPLOYED, 1, 1),
                AttackKind.CREEPER, Optional.empty(), List.of());
        return new CombatEvent(round, UUID.randomUUID(), batch, 1, victim, Optional.of(origin), CombatEvent.Attribution.MONSTER,
                "minecraft:explosion", Optional.empty(), Optional.empty(), 20, true, clock.get());
    }
    Path seed() throws Exception {
        Path path = temp.toRealPath().resolve("batch.sqlite");
        var monster = new Participant(hunter, "Hunter", ParticipantRole.MONSTER_LOBBY, false, false, 100, 0, 1, false,
                true, MonsterRole.CREEPER, MonsterDimension.OVERWORLD, 0, 0);
        var human = new Participant(victim, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 100, 0, 1, false);
        var snapshot = new RoundSnapshot(round, 1, 9, 0, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, Map.of(hunter, monster, victim, human), true, false, null);
        try (var store = new SqliteRoundStore(path)) {
            assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(round, -1, 0, RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "FIXTURE")));
        }
        return path;
    }
    P1LobbySession open(Path path) throws Exception {
        var session = new P1LobbySession(path, clock::get, callbacks::add);
        pump(() -> session.state() == P1LobbySession.State.OPEN);
        session.adaptersReady(round);
        pump(() -> !session.mutationPending());
        session.onOnline(hunter, "Hunter", true);
        pump(() -> !session.mutationPending());
        var monster = session.snapshot().orElseThrow().participants().get(hunter);
        if (monster.monsterDeploymentSequence() == 0) {
            var reply = new AtomicReference<P1LobbySession.Reply>();
            session.requestMonsterDeploy(round, hunter, 1, 0, reply::set);
            pump(() -> reply.get() != null);
            assertTrue(reply.get().success(), reply.get().message());
        }
        return session;
    }
    void pump(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            var callback = callbacks.poll(100, TimeUnit.MILLISECONDS);
            if (callback != null) callback.run();
        }
        assertTrue(done.getAsBoolean(), "server callback timeout");
    }
}
