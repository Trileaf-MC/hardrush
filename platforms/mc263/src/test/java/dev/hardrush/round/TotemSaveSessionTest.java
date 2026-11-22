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

/** {@link RoundSession#captureTotemSave}: the synchronous booking of an in-place revival (docs/plan/复生图腾掉落.md 2.5). */
class TotemSaveSessionTest {
    @TempDir Path temp;
    final UUID round = UUID.randomUUID(), hunter = UUID.randomUUID(), human = UUID.randomUUID(), rival = UUID.randomUUID();
    final AtomicLong clock = new AtomicLong();
    final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
    final List<String> committed = new ArrayList<>();

    @Test void aSaveIsBookedAtOnceCountsInFlightUntilItsCommitAndSpendsOneCharge() throws Exception {
        try (var session = open(seed(2))) {
            assertTrue(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()));
            assertEquals(1, session.pendingTotemSaves(human), "In flight from the moment the totem pops");
            assertEquals(2, charges(session), "Nothing is committed yet");
            assertTrue(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()), "A second charge can save again before the first commits");
            assertEquals(2, session.pendingTotemSaves(human));
            assertFalse(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()), "Every committed charge is already in flight");
            assertEquals(2, session.pendingTotemSaves(human), "A refused save books nothing");
            pump(() -> !session.mutationPending() && session.pendingTotemSaves(human) == 0);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(0, charges(session));
            assertEquals(List.of("TOTEM_SAVE", "TOTEM_SAVE"), saves());
            var player = session.snapshot().orElseThrow().participants().get(human);
            assertEquals(ParticipantRole.HUMAN_ACTIVE, player.role(), "Saved in place: still the same human life");
            assertEquals(1, player.lifeSequence());
            assertFalse(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()), "No charge left");
        }
    }

    @Test void onlyTheCommittedActiveHumanOfThisRoundAndLifeWithAChargeCanBeSaved() throws Exception {
        try (var session = open(seed(1))) {
            assertFalse(session.captureTotemSave(UUID.randomUUID(), human, 1, Optional.empty(), Optional.empty()), "Another round");
            assertFalse(session.captureTotemSave(round, human, 2, Optional.empty(), Optional.empty()), "Another life");
            assertFalse(session.captureTotemSave(round, hunter, 1, Optional.empty(), Optional.empty()), "A monster player");
            assertFalse(session.captureTotemSave(round, UUID.randomUUID(), 1, Optional.empty(), Optional.empty()), "Not a participant");
            assertEquals(0, session.pendingTotemSaves(human));
            assertEquals(0, session.pendingTotemSaves(hunter));
            assertTrue(saves().isEmpty(), "Nothing was queued");
        }
        try (var session = open(seed(0))) {
            assertFalse(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()), "No committed charge");
        }
    }

    @Test void aLockedStoppingOrNotYetReadySessionSavesNobody() throws Exception {
        try (var session = new RoundSession(seed(3), clock::get, callbacks::add)) {
            pump(() -> session.state() == RoundSession.State.OPEN);
            assertFalse(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()), "Adapters not ready");
            assertEquals(0, session.pendingTotemSaves(human));
        }
        try (var session = open(seed(3))) {
            assertTrue(session.beginFullCheckpoint(), "A full checkpoint can start");
            assertTrue(session.competitionLocked());
            assertFalse(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()), "Locked while a full checkpoint saves");
            session.endFullCheckpoint();
            pump(() -> !session.mutationPending());
            session.prepareStop();
            assertFalse(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()), "Past the shutdown boundary");
            assertEquals(0, session.pendingTotemSaves(human));
        }
        try (var session = open(seed(3))) {
            session.close();
            assertFalse(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()), "A closed session");
        }
    }

    @Test void aFullEventQueueRefusesTheSaveInsteadOfFailingTheSession() throws Exception {
        try (var session = open(seed(400))) {
            int booked = 0;
            // Callbacks are not pumped: the first save is the write in flight, the rest wait in the queue.
            while (session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty())) booked++;
            assertEquals(1 + 256, booked, "The write in flight plus a full queue");
            assertEquals(booked, session.pendingTotemSaves(human), "Exactly the queued saves are in flight");
            assertEquals(RoundSession.State.OPEN, session.state(), "A refused save does not fail the session");
            pump(() -> !session.mutationPending() && session.pendingTotemSaves(human) == 0);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(400 - booked, charges(session));
        }
    }

    @Test void aLethalHitInTheSameTickAfterThePopSpendsTheChargeOnceAndTheDeathFollowsIt() throws Exception {
        try (var session = open(seed(1))) {
            assertTrue(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()));
            // The stack in hand is gone; the next lethal hit of the same tick kills, and its batch queues behind the save.
            assertFalse(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()), "The only charge is in flight");
            UUID batch = UUID.randomUUID();
            assertTrue(session.captureDeath(round, human, 1, 0, batch, Optional.empty(), false, Optional.empty()));
            session.finishDamageBatch(round, batch);
            pump(() -> !session.mutationPending() && session.pendingTotemSaves(human) == 0);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(List.of("TOTEM_SAVE"), saves(), "Never voided");
            assertTrue(committed.indexOf("TOTEM_SAVE") < committed.indexOf("DEATH_BATCH"), "The save commits before the death: " + committed);
            var player = session.snapshot().orElseThrow().participants().get(human);
            assertEquals(ParticipantRole.MONSTER_LOBBY, player.role(), "The second lethal hit killed");
            assertEquals(0, player.revivalCharges(), "The charge was spent once, by the save");
        }
    }

    @Test void aSaveVoidedByAnEarlierDeathIsStillReleased() throws Exception {
        try (var session = open(seed(1))) {
            UUID batch = UUID.randomUUID();
            assertTrue(session.captureDeath(round, human, 1, 0, batch, Optional.empty(), false, Optional.empty()));
            session.finishDamageBatch(round, batch);
            assertTrue(session.mutationPending(), "The death is being written");
            // The committed snapshot still shows the life: the session books, the engine voids it behind the death.
            assertTrue(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()));
            assertEquals(1, session.pendingTotemSaves(human));
            pump(() -> !session.mutationPending() && saves().contains("TOTEM_SAVE_VOID"));
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(0, session.pendingTotemSaves(human), "Released on the void commit");
            var player = session.snapshot().orElseThrow().participants().get(human);
            assertEquals(ParticipantRole.MONSTER_LOBBY, player.role());
            assertEquals(1, player.revivalCharges(), "A void spends nothing");
        }
    }

    @Test void aMonsterThatForcesTheTotemEarnsTheKill() throws Exception {
        try (var session = open(seed(2))) {
            var monster = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(hunter, ParticipantRole.MONSTER_DEPLOYED, 1, 1),
                    AttackKind.MELEE, Optional.empty(), List.of());
            assertTrue(session.captureTotemSave(round, human, 1, Optional.of(monster), Optional.empty()));
            pump(() -> !session.mutationPending() && session.pendingTotemSaves(human) == 0);
            var after = session.snapshot().orElseThrow();
            assertEquals(1, after.participants().get(human).revivalCharges());
            assertEquals(1, after.participants().get(hunter).revivalCharges(), "Forcing the totem counts as the monster's kill");
        }
    }

    @Test void aHumanThatForcesTheTotemRegistersItsDropOnceTheSaveCommits() throws Exception {
        try (var session = open(seed(2, true))) {
            var hit = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(rival, ParticipantRole.HUMAN_ACTIVE, 1, 0),
                    AttackKind.MELEE, Optional.empty(), List.of());
            UUID token = UUID.randomUUID(), unknown = UUID.randomUUID();
            var seen = new ArrayList<Boolean>();
            // The committed listener runs after the save is released: it sees the registration, no longer the flight.
            session.onCommitted((before, after) -> {
                if (session.committedEventType().startsWith("TOTEM_SAVE")) seen.add(session.totemSaveDropInFlight(token));
                committed.add(session.committedEventType());
            });
            assertTrue(session.captureTotemSave(round, human, 1, Optional.of(hit), Optional.of(token)));
            assertTrue(session.totemSaveDropInFlight(token), "In flight until its save commits");
            assertFalse(session.totemSaveDropInFlight(unknown));
            pump(() -> !session.mutationPending() && session.pendingTotemSaves(human) == 0);
            assertEquals(List.of(false), seen);
            assertFalse(session.totemSaveDropInFlight(token));
            var after = session.snapshot().orElseThrow();
            assertEquals(1, after.participants().get(human).revivalCharges());
            assertEquals(0, after.participants().get(rival).revivalCharges(), "The attacker earns nothing by itself");
            assertEquals(new LooseTotem(human, 1, rival, after.looseTotems().get(token).droppedAtChallengeNanos()),
                    after.looseTotems().get(token));
        }
    }

    @Test void aVoidSaveReleasesItsDropUnregistered() throws Exception {
        try (var session = open(seed(1, true))) {
            var hit = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(rival, ParticipantRole.HUMAN_ACTIVE, 1, 0),
                    AttackKind.MELEE, Optional.empty(), List.of());
            UUID batch = UUID.randomUUID(), token = UUID.randomUUID();
            assertTrue(session.captureDeath(round, human, 1, 0, batch, Optional.empty(), false, Optional.empty()));
            session.finishDamageBatch(round, batch);
            assertTrue(session.captureTotemSave(round, human, 1, Optional.of(hit), Optional.of(token)));
            pump(() -> !session.mutationPending() && saves().contains("TOTEM_SAVE_VOID"));
            assertFalse(session.totemSaveDropInFlight(token));
            assertTrue(session.snapshot().orElseThrow().looseTotems().isEmpty());
        }
    }

    @Test void closingTheSessionDrainsAndReleasesEveryQueuedSave() throws Exception {
        Path path = seed(2);
        var session = open(path);
        assertTrue(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()));
        assertTrue(session.captureTotemSave(round, human, 1, Optional.empty(), Optional.empty()));
        session.close();
        assertEquals(0, session.pendingTotemSaves(human));
        try (var store = new SqliteRoundStore(path)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertEquals(0, saved.participants().get(human).revivalCharges(), "Both saves were written before the stop");
        }
    }

    int charges(RoundSession session) { return session.snapshot().orElseThrow().participants().get(human).revivalCharges(); }

    List<String> saves() { return committed.stream().filter(type -> type.startsWith("TOTEM_SAVE")).toList(); }

    Path seed(int charges) throws Exception { return seed(charges, false); }

    /** {@code withRival} adds a second active human, the one that forces a totem out. */
    Path seed(int charges, boolean withRival) throws Exception {
        Path path = temp.toRealPath().resolve("save-" + UUID.randomUUID() + ".sqlite");
        var monster = new Participant(hunter, "Hunter", ParticipantRole.MONSTER_LOBBY, false, false, 100, 0, 1, false,
                MonsterRole.CREEPER, MonsterDimension.OVERWORLD, 0, 0);
        var player = new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 100, 0, 1, charges,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, false);
        var players = new LinkedHashMap<UUID, Participant>(Map.of(hunter, monster, human, player));
        if (withRival) players.put(rival, new Participant(rival, "Rival", ParticipantRole.HUMAN_ACTIVE, false, false, 100, 0, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, false));
        var snapshot = new RoundSnapshot(round, 1, 9, 0, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null);
        try (var store = new SqliteRoundStore(path)) {
            assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(round, -1, 0, RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "FIXTURE")));
        }
        return path;
    }

    RoundSession open(Path path) throws Exception {
        committed.clear();
        var session = new RoundSession(path, clock::get, callbacks::add);
        pump(() -> session.state() == RoundSession.State.OPEN);
        session.adaptersReady(round);
        pump(() -> !session.mutationPending());
        session.onOnline(human, "Human", true);
        pump(() -> !session.mutationPending());
        session.onOnline(hunter, "Hunter", true);
        pump(() -> !session.mutationPending());
        var reply = new AtomicReference<RoundSession.Reply>();
        session.requestMonsterDeploy(round, hunter, 1, 0, reply::set);
        pump(() -> reply.get() != null);
        assertTrue(reply.get().success(), reply.get().message());
        session.onCommitted((before, after) -> committed.add(session.committedEventType()));
        return session;
    }

    void pump(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            var callback = callbacks.poll(100, TimeUnit.MILLISECONDS);
            if (callback != null) callback.run();
        }
        assertTrue(done.getAsBoolean(), "server callback timeout");
    }
}
