package dev.hardrush.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.*;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ordering of buffered damage coins against terminal, death and disconnect commits; real SQLite, no client. */
class MonsterCoinTimingSessionTest {
    @TempDir Path temporary;
    private final UUID round = UUID.randomUUID(), human = UUID.randomUUID(), other = UUID.randomUUID(),
            monster = UUID.randomUUID(), dragon = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();
    private final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    @Test void pendingDamageBeforeTheDragonIsCreditedOnTheTerminalTimeAndTheWinnerStands() throws Exception {
        try (var session = open(seed())) {
            clock.set(seconds(5));
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 4_000);
            var lethal = dragonKill(seconds(7));
            session.captureDragonDeath(lethal, dragon);
            // The batch closes later than the lethal hit; a flush on this later time used to run first.
            clock.set(seconds(9));
            session.finishDamageBatch(round, lethal.batchId());
            pump(() -> session.state() == RoundSession.State.FAILED
                    || session.snapshot().orElseThrow().result() != null && !session.mutationPending());
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            var settled = session.snapshot().orElseThrow();
            assertEquals(RoundResult.Kind.COMPLETED, settled.result().kind());
            assertEquals(human, settled.result().winner());
            assertEquals(seconds(7), settled.challengeNanos(), "The race stops at the dragon, not at the coin flush");
            assertEquals(4, settled.participants().get(monster).wallet().coins(), "Damage before the dragon is paid once");
        }
    }

    @Test void aTerminalWithoutPendingDamageStillSettles() throws Exception {
        try (var session = open(seed())) {
            clock.set(seconds(7));
            var lethal = dragonKill(clock.get());
            session.captureDragonDeath(lethal, dragon);
            clock.set(seconds(9));
            session.finishDamageBatch(round, lethal.batchId());
            pump(() -> session.snapshot().orElseThrow().result() != null && !session.mutationPending());
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(human, session.snapshot().orElseThrow().result().winner());
            assertEquals(0, session.snapshot().orElseThrow().participants().get(monster).wallet().coins());
        }
    }

    @Test void aSecondBatchKillingTheRecalledHunterIsSettledNotFatal() throws Exception {
        Path path = seed();
        try (var session = open(path)) {
            clock.set(seconds(5));
            var first = UUID.randomUUID();
            session.captureDeath(round, human, 1, 0, first);
            session.captureLethalEvidence(lethal(first, human, new CombatActor(monster, ParticipantRole.MONSTER_DEPLOYED, 1, 3)));
            session.finishDamageBatch(round, first);
            assertTrue(session.mutationPending(), "The kill's batch is still being written");
            // Before that write completes, another human kills the still-deployed hunter in a separate batch.
            var second = UUID.randomUUID();
            session.captureDeath(round, monster, 1, 3, second, true);
            session.captureLethalEvidence(lethal(second, monster, new CombatActor(other, ParticipantRole.HUMAN_ACTIVE, 1, 0)));
            session.finishDamageBatch(round, second);
            clock.set(seconds(6));
            settle(session);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            var hunter = session.snapshot().orElseThrow().participants().get(monster);
            assertEquals(ParticipantRole.MONSTER_LOBBY, hunter.role());
            assertEquals(3, hunter.monsterDeploymentSequence(), "The recalled sortie is not ended twice");
            assertEquals(1, hunter.revivalCharges());
            assertEquals(0, hunter.wallet().coins(), "The already-ended sortie earns nothing from its later death");
            var victim = session.snapshot().orElseThrow().participants().get(human);
            assertEquals(ParticipantRole.MONSTER_LOBBY, victim.role());
            assertEquals(6, victim.wallet().coins(), "The slain human becomes a monster with the gift");
            assertEquals(RoundPhase.RUNNING, session.snapshot().orElseThrow().phase(), "Another human is still racing");
        }
        try (var store = new SqliteRoundStore(path)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).participants().get(monster);
            assertEquals(1, saved.revivalCharges());
            assertEquals(ParticipantRole.MONSTER_LOBBY, saved.role());
        }
    }

    @Test void damageBufferedBeforeADisconnectIsPaidOnceAndNeverToTheNextSortie() throws Exception {
        Path path = seed();
        try (var session = open(path)) {
            clock.set(seconds(5));
            session.tick();
            pump(() -> !session.mutationPending());
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 2_500);
            session.onOnline(monster, "Monster", false);
            pump(() -> !session.mutationPending());
            assertEquals(ParticipantRole.MONSTER_LOBBY, session.snapshot().orElseThrow().participants().get(monster).role(),
                    "The disconnect ends the sortie before the next one-second flush");
            clock.addAndGet(seconds(1));
            session.tick();
            pump(() -> !session.mutationPending());
            assertEquals(2, coins(session), "The late flush still belongs to the ended sortie");
            clock.addAndGet(seconds(1));
            session.tick();
            pump(() -> !session.mutationPending());
            assertEquals(2, coins(session), "A later flush never pays it again");

            session.onOnline(monster, "Monster", true);
            pump(() -> !session.mutationPending());
            clock.addAndGet(seconds(31));
            request(reply -> session.requestMonsterDeploy(round, monster, 1, 3, reply));
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 9_000);
            session.recordMonsterDamage(round, monster, 1, 4, human, MonsterDimension.OVERWORLD, 1_000);
            clock.addAndGet(seconds(1));
            session.tick();
            pump(() -> !session.mutationPending());
            assertEquals(3, coins(session), "Only the new sortie's damage counts; the old sortie's late entry is refused");
            // A normal stop credits what is still buffered inside the stop boundary.
            session.recordMonsterDamage(round, monster, 1, 4, human, MonsterDimension.OVERWORLD, 1_500);
        }
        try (var session = open(path)) {
            assertEquals(4, coins(session), "Buffered damage survives a normal stop exactly once");
        }
    }

    @Test void recentPlayerDamageSurvivesEnvironmentalEvidenceAndDuplicateDeathCallbacks() throws Exception {
        Path path = seed();
        try (var session = open(path)) {
            var batch = UUID.randomUUID();
            session.captureDeath(round, monster, 1, 3, batch, true);
            // AFTER_DEATH is followed by the final environmental hit's evidence, with no player origin.
            session.captureLethalEvidence(new CombatEvent(round, UUID.randomUUID(), batch, 1, monster,
                    Optional.empty(), CombatEvent.Attribution.PENDING_VERIFICATION, "minecraft:fall",
                    Optional.empty(), Optional.empty(), 20, true, clock.get()));
            session.captureDeath(round, monster, 1, 3, batch, false);
            session.finishDamageBatch(round, batch);
            settle(session);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(5, coins(session), "Later evidence must preserve the native memory captured at death");
            session.captureDeath(round, monster, 1, 3, UUID.randomUUID(), true);
            session.finishDamageBatch(round, batch);
            settle(session);
            assertEquals(5, coins(session), "Repeated callbacks cannot award the death twice");
        }
        try (var store = new SqliteRoundStore(path)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).participants().get(monster);
            assertEquals(5, saved.wallet().coins(), "The environmental death reward is durable");
        }
    }

    @Test void recentPlayerDamageRewardsADeathWithoutACombatBatch() throws Exception {
        try (var session = open(seed())) {
            session.captureDeath(round, monster, 1, 3, null, true);
            settle(session);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(5, coins(session));
        }
    }

    @Test void worthCountsBufferedQueuedPendingAndCommittedDamageExactlyOnce() throws Exception {
        try (var session = open(seed())) {
            assertEquals(OptionalInt.of(100), session.monsterWorth(monster), "A fresh sortie is worth the base");
            clock.set(seconds(5));
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 4_000);
            assertEquals(OptionalInt.of(140), session.monsterWorth(monster), "A buffered hit counts before any flush");
            clock.set(seconds(6));
            session.tick();
            assertTrue(session.mutationPending(), "The first flush is being written");
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 2_000);
            clock.set(seconds(7));
            session.tick();
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 1_000);
            assertEquals(OptionalInt.of(170), session.monsterWorth(monster),
                    "The pending write, the queued flush behind it and the buffer each count once");
            clock.set(seconds(8));
            settle(session);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(7, coins(session));
            assertEquals(OptionalInt.of(170), session.monsterWorth(monster));
            assertTrue(session.monsterWorth(human).isEmpty(), "Only a deployed monster body has worth");
            session.captureDeath(round, monster, 1, 3, null, true);
            settle(session);
            assertTrue(session.monsterWorth(monster).isEmpty(), "A body back in the camp has no worth");
        }
    }

    @Test void deathWorthUsesTheTenMinuteBountyForBufferedDamageAfterAPendingFlush() throws Exception {
        // 1 point at x1 is still being written; 4 buffered points after 10 minutes are worth x1.25 = 5.
        assertDeathWorthAcrossTier(600, false, 160, 6);
    }

    @Test void deathWorthUsesTheTenMinuteBountyForAQueuedFlushBehindAPendingOne() throws Exception {
        assertDeathWorthAcrossTier(600, true, 160, 6);
    }

    @Test void deathWorthUsesTheThirtyMinuteBountyAcrossThePendingFlush() throws Exception {
        // 1 point at x1.25 before 30 minutes, then 4 points at x1.5 = 6: 100 + 12 + 60.
        assertDeathWorthAcrossTier(1800, true, 172, 7);
    }

    @Test void queuedAndBufferedDamageOnBothSidesOfTheTierKeepTheirOwnEventTimes() throws Exception {
        try (var session = open(seed())) {
            clock.set(seconds(598));
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 1_000);
            session.tick();
            assertTrue(session.mutationPending(), "The first flush stays unwritten");
            // Recorded before the tier, flushed at 599 s: still x1.
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 2_000);
            clock.set(seconds(599));
            session.tick();
            // Recorded before the tier but flushed at 601 s: the formal path counts it at x1.25.
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 2_000);
            clock.set(seconds(601));
            session.tick();
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 4_000);
            // 1 + 2 at x1, 2 + 4 at x1.25: weighted 10.5 points.
            assertEquals(OptionalInt.of(205), session.monsterWorth(monster));
            session.captureDeath(round, monster, 1, 3, null, true);
            settle(session);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(205, committedWorth(session), "The formal credit matches the worth read at death");
            assertEquals(10 + 5, coins(session), "Each flush is paid once, plus the death reward");
        }
    }

    /**
     * A flush just before the human's survival tier stays unwritten; later damage after the tier is
     * queued or buffered when the monster dies. The worth read at death must equal the formal credit.
     */
    private void assertDeathWorthAcrossTier(long tierSeconds, boolean queued, int expectedWorth, int expectedCoins) throws Exception {
        try (var session = open(seed())) {
            clock.set(seconds(tierSeconds - 1));
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 1_000);
            session.tick();
            assertTrue(session.mutationPending(), "The pre-tier flush stays unwritten");
            clock.set(seconds(tierSeconds + 1));
            session.recordMonsterDamage(round, monster, 1, 3, human, MonsterDimension.OVERWORLD, 4_000);
            if (queued) session.tick();
            assertTrue(session.mutationPending(), "The pre-tier write is still pending at death");
            int worth = session.monsterWorth(monster).orElseThrow();
            assertEquals(expectedWorth, worth, "The death reward reads the bounty of each flush's own time");
            assertEquals(expectedWorth >= 160, dev.hardrush.core.round.MonsterWorth.bookCost(
                    session.snapshot().orElseThrow().rules(), worth) > 0, "The book follows the same worth");
            session.captureDeath(round, monster, 1, 3, null, true);
            settle(session);
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
            assertEquals(expectedWorth, committedWorth(session), "The formal credit matches the worth read at death");
            assertEquals(expectedCoins + 5, coins(session), "Damage is paid once, plus the death reward");
            assertTrue(session.monsterWorth(monster).isEmpty(), "The settled body has no worth left to drop again");
        }
    }

    private int committedWorth(RoundSession session) {
        var round = session.snapshot().orElseThrow();
        return dev.hardrush.core.round.MonsterWorth.experience(round.rules(), round.participants().get(monster).wallet());
    }

    private int coins(RoundSession session) { return session.snapshot().orElseThrow().participants().get(monster).wallet().coins(); }

    private static long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }

    private CombatEvent dragonKill(long time) {
        var actor = new CombatActor(human, ParticipantRole.HUMAN_ACTIVE, 1, 0);
        var origin = new CombatOrigin(round, UUID.randomUUID(), actor, AttackKind.ARROW, Optional.of(UUID.randomUUID()), List.of());
        return new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 1, dragon, Optional.of(origin),
                CombatEvent.Attribution.HUMAN, "minecraft:arrow", origin.directEntityId(), Optional.of(human), 1, true, time);
    }

    private CombatEvent lethal(UUID batch, UUID victim, CombatActor actor) {
        var origin = Optional.of(new CombatOrigin(round, UUID.randomUUID(), actor, AttackKind.MELEE, Optional.empty(), List.of()));
        return new CombatEvent(round, UUID.randomUUID(), batch, 1, victim, origin, CombatEvent.classify(round, origin),
                "minecraft:mob_attack", Optional.empty(), Optional.of(actor.playerId()), 20, true, clock.get());
    }

    private Path seed() throws Exception {
        var path = temporary.toRealPath().resolve("coins.sqlite");
        var players = new LinkedHashMap<UUID, Participant>();
        players.put(human, new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 100, 0, 1, false, true));
        players.put(other, new Participant(other, "Other", ParticipantRole.HUMAN_ACTIVE, false, false, 100, 0, 1, false, true));
        players.put(monster, new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, false, false,
                100, 0, 1, false, true, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2));
        var snapshot = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 0, 0, players, true, false, null);
        try (var store = new SqliteRoundStore(path)) {
            store.commit(new SnapshotWrite(round, -1, 0, RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "FIXTURE"));
        }
        return path;
    }

    /** Opens the round with every participant online and the hunter deployed as sortie 3. */
    private RoundSession open(Path path) throws Exception {
        var session = new RoundSession(path, clock::get, callbacks::add);
        pump(() -> session.state() == RoundSession.State.OPEN);
        session.adaptersReady(round);
        pump(() -> !session.mutationPending());
        for (var id : List.of(human, other, monster)) {
            session.onOnline(id, id == human ? "Human" : id == other ? "Other" : "Monster", true);
            pump(() -> !session.mutationPending());
        }
        var hunter = session.snapshot().orElseThrow().participants().get(monster);
        if (hunter.monsterDeploymentSequence() == 2) request(reply -> session.requestMonsterDeploy(round, monster, 1, 2, reply));
        return session;
    }

    private void request(java.util.function.Consumer<java.util.function.Consumer<RoundSession.Reply>> action) throws Exception {
        var answer = new AtomicReference<RoundSession.Reply>();
        action.accept(answer::set);
        pump(() -> answer.get() != null);
        assertTrue(answer.get().success(), answer.get().message());
    }

    /** Commits every queued event, including ones waiting behind an in-flight write. */
    private void settle(RoundSession session) throws Exception {
        for (int i = 0; i < 6; i++) {
            pump(() -> !session.mutationPending());
            session.tick();
        }
        pump(() -> !session.mutationPending());
    }

    private void pump(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            var callback = callbacks.poll(50, TimeUnit.MILLISECONDS);
            if (callback != null) callback.run();
        }
        assertTrue(done.getAsBoolean(), "server callback timeout");
    }
}
