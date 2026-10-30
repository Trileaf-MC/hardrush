package dev.hardrush.round;

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

class DeathBatchSessionTest {
    @TempDir Path temp;
    final UUID round = UUID.randomUUID(), hunter = UUID.randomUUID(), victim = UUID.randomUUID();
    final AtomicLong clock = new AtomicLong();
    final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    @Test void humanKillPersistsOneChargeBeforeDeathAndLaterRevivalConsumesItExactlyOnce() throws Exception {
        Path path = seed(ParticipantRole.HUMAN_ACTIVE);
        try (var session = open(path)) {
            UUID batch = UUID.randomUUID();
            var origin = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(hunter, ParticipantRole.HUMAN_ACTIVE, 1, 0),
                    AttackKind.MELEE, Optional.empty(), List.of());
            var cause = new CombatEvent(round, UUID.randomUUID(), batch, 1, victim, Optional.of(origin),
                    CombatEvent.Attribution.HUMAN, "minecraft:player_attack", Optional.of(hunter), Optional.of(hunter),
                    20, true, clock.get());
            session.captureDeath(round, victim, 1, 0, batch);
            session.captureLethalEvidence(cause);
            session.captureLethalEvidence(cause);
            session.finishDamageBatch(round, batch);
            assertEquals(0, session.snapshot().orElseThrow().participants().get(hunter).revivalCharges());
            pump(() -> !session.mutationPending());
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(1, session.snapshot().orElseThrow().participants().get(hunter).revivalCharges());
            assertEquals(ParticipantRole.HUMAN_ACTIVE, session.snapshot().orElseThrow().participants().get(hunter).role());
            session.finishDamageBatch(round, batch);
            assertFalse(session.mutationPending());

            UUID later = UUID.randomUUID();
            session.captureDeath(round, hunter, 1, 0, later);
            session.finishDamageBatch(round, later);
            pump(() -> !session.mutationPending());
            assertEquals(RoundPhase.RESCUE, session.snapshot().orElseThrow().phase());
            assertEquals(1, session.snapshot().orElseThrow().participants().get(hunter).revivalCharges());
            var reply = new AtomicReference<RoundSession.Reply>();
            session.requestRevival(round, hunter, 1, 0, reply::set);
            pump(() -> reply.get() != null);
            assertTrue(reply.get().success(), reply.get().message());
            assertEquals(0, session.snapshot().orElseThrow().participants().get(hunter).revivalCharges());
        }
        try (var store = new SqliteRoundStore(path)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertEquals(0, saved.participants().get(hunter).revivalCharges());
            assertEquals(2, saved.participants().get(hunter).lifeSequence());
            assertEquals(1, store.participantRecord(round, hunter).orElseThrow().revivalCount());
            assertEquals(1, store.participantRecord(round, victim).orElseThrow().deathCount());
        }
    }

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
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            var saved = session.snapshot().orElseThrow();
            assertEquals(RoundPhase.RESCUE, saved.phase());
            assertTrue(saved.participants().get(hunter).revivalEntitled());
            assertEquals(ParticipantRole.MONSTER_LOBBY, saved.participants().get(hunter).role());
            long version = saved.version();
            session.finishDamageBatch(round, batch);
            assertEquals(version, session.snapshot().orElseThrow().version());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(59));
            var first = new AtomicReference<RoundSession.Reply>();
            var duplicate = new AtomicReference<RoundSession.Reply>();
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
            // The kill's revival sent the hunter straight back to the camp while rescue runs.
            assertEquals(ParticipantRole.MONSTER_LOBBY, session.snapshot().orElseThrow().participants().get(hunter).role());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(20));
        }
        try (var session = open(path)) {
            assertEquals(40_000, session.view().orElseThrow().remainingPhaseMillis());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(39));
            var selection = new AtomicReference<RoundSession.Reply>();
            session.requestRevivalTotemDrop(round, hunter, 1, 1, 1, selection::set);
            assertTrue(session.mutationPending());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            assertTrue(session.competitionLocked(), "An unrelated pending write must not extend the rescue deadline");
            pump(() -> selection.get() != null);
            var reply = new AtomicReference<RoundSession.Reply>();
            session.requestRevival(round, hunter, 1, 1, reply::set);
            assertFalse(reply.get().success());
            session.tick();
            pump(() -> !session.mutationPending());
            assertEquals(RoundResult.Kind.FAILED, session.snapshot().orElseThrow().result().kind());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            session.tick();
            pump(() -> !session.mutationPending());
            var failed = session.snapshot().orElseThrow();
            assertEquals(RoundPhase.SETTLING, failed.phase());
            assertEquals(29_000, failed.remainingPhaseMillis());
            assertTrue(dev.hardrush.core.record.RoundTab.from(failed, 0, null).header().contains("挑战失败"));
            clock.addAndGet(TimeUnit.SECONDS.toNanos(28));
            session.tick();
            pump(() -> !session.mutationPending());
            assertEquals(RoundPhase.SETTLING, session.snapshot().orElseThrow().phase());
            assertEquals(1_000, session.snapshot().orElseThrow().remainingPhaseMillis());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            session.tick();
            pump(() -> !session.mutationPending());
            assertEquals(RoundPhase.ROTATING, session.snapshot().orElseThrow().phase());
            assertEquals(failed.result(), session.snapshot().orElseThrow().result());
        }
    }

    CombatEvent lethal(UUID batch) {
        var origin = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(hunter, ParticipantRole.MONSTER_DEPLOYED, 1, 1),
                AttackKind.CREEPER, Optional.empty(), List.of());
        return new CombatEvent(round, UUID.randomUUID(), batch, 1, victim, Optional.of(origin), CombatEvent.Attribution.MONSTER,
                "minecraft:explosion", Optional.empty(), Optional.empty(), 20, true, clock.get());
    }
    Path seed() throws Exception {
        return seed(ParticipantRole.MONSTER_LOBBY);
    }
    Path seed(ParticipantRole hunterRole) throws Exception {
        Path path = temp.toRealPath().resolve("batch.sqlite");
        var monster = new Participant(hunter, "Hunter", hunterRole, false, false, 100, 0, 1, false,
                true, MonsterRole.CREEPER, MonsterDimension.OVERWORLD, 0, 0);
        var human = new Participant(victim, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 100, 0, 1, false);
        var snapshot = new RoundSnapshot(round, 1, 9, 0, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, Map.of(hunter, monster, victim, human), true, false, null);
        try (var store = new SqliteRoundStore(path)) {
            assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(round, -1, 0, RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "FIXTURE")));
        }
        return path;
    }
    RoundSession open(Path path) throws Exception {
        var session = new RoundSession(path, clock::get, callbacks::add);
        pump(() -> session.state() == RoundSession.State.OPEN);
        session.adaptersReady(round);
        pump(() -> !session.mutationPending());
        session.onOnline(hunter, "Hunter", true);
        pump(() -> !session.mutationPending());
        var monster = session.snapshot().orElseThrow().participants().get(hunter);
        if (monster.role().isMonster() && monster.monsterDeploymentSequence() == 0) {
            var reply = new AtomicReference<RoundSession.Reply>();
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
