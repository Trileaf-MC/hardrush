package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Dropped revival totems and in-place saves (docs/plan/复生图腾掉落.md, 1.1, 1.3, 1.5 and 2.1). */
class LooseTotemTest {
    private final UUID round = UUID.randomUUID();
    private final UUID killer = UUID.randomUUID();
    private final UUID victim = UUID.randomUUID();
    private final UUID bystander = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();
    private final UUID batch = UUID.randomUUID();
    private final Clock clock = new Clock();

    private static final class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
        void seconds(long value) { now += value * 1_000_000_000L; }
    }

    private static void apply(RoundEngine engine, RoundTransition event) { assertTrue(engine.accept(event)); }

    private static Participant human(UUID id) {
        return new Participant(id, "Human", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 50, 1, false);
    }

    private Participant monster(int charges) {
        return new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false,
                70, 20, 1, charges, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 3, Map.of());
    }

    private RoundEngine fixture(GameMode mode, Participant... participants) {
        return fixture(mode, Map.of(), participants);
    }

    private RoundEngine fixture(GameMode mode, Map<UUID, LooseTotem> totems, Participant... participants) {
        Map<UUID, Participant> players = new LinkedHashMap<>();
        for (var p : participants) players.put(p.id(), p);
        var stored = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults().withMode(mode),
                RoundPhase.RUNNING, PauseReason.SERVER_STOPPED, 100, 0, players, true, false, null, totems);
        var engine = RoundEngine.restore(stored, clock);
        for (var p : participants) if (p.online()) apply(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        return engine;
    }

    private CombatOrigin origin(UUID owner, ParticipantRole role, int life, int sortie, AttackKind kind) {
        return new CombatOrigin(round, UUID.randomUUID(), new CombatActor(owner, role, life, sortie), kind,
                switch (kind) {
                    case ARROW, TNT, END_CRYSTAL -> Optional.of(UUID.randomUUID());
                    default -> Optional.empty();
                }, List.of());
    }

    private CombatOrigin humanHit(UUID owner) { return origin(owner, ParticipantRole.HUMAN_ACTIVE, 1, 0, AttackKind.MELEE); }

    private CombatOrigin monsterHit() { return origin(monster, ParticipantRole.MONSTER_DEPLOYED, 1, 4, AttackKind.MELEE); }

    private RoundDeath death(RoundEngine engine, UUID id, CombatOrigin credit, UUID token) {
        var p = engine.view().participants().get(id);
        var source = Optional.ofNullable(credit);
        var lethal = new CombatEvent(round, UUID.randomUUID(), batch, 1, id, source, CombatEvent.classify(round, source),
                "test:actual_lethal", Optional.empty(), Optional.empty(), 20, true, clock.now);
        return new RoundDeath(id, p.lifeSequence(), p.monsterDeploymentSequence(), Optional.of(lethal), source, false,
                Optional.ofNullable(token));
    }

    private RoundTransition batch(RoundEngine engine, RoundDeath... deaths) {
        return engine.proposeDeathBatch(UUID.randomUUID(), round, List.of(deaths), null);
    }

    /** {@code killer} kills {@code victim}; the bystander keeps the round going. */
    private RoundEngine dropped(UUID token) {
        var engine = fixture(GameMode.HARDCORE, human(killer), human(victim), human(bystander));
        apply(engine, batch(engine, death(engine, victim, humanHit(killer), token)));
        assertTrue(engine.view().looseTotems().containsKey(token));
        return engine;
    }

    private Participant player(RoundEngine engine, UUID id) { return engine.view().participants().get(id); }

    // ---- Registration (plan 1.1) ----

    @Test void aHumanKillRegistersTheDropAtItsChallengeTimeAndPaysTheKillerNothing() {
        var engine = fixture(GameMode.HARDCORE, human(killer), human(victim));
        var token = UUID.randomUUID();
        clock.seconds(7);
        var death = death(engine, victim, humanHit(killer), token);
        assertTrue(TotemDrop.qualifies(engine.view(), death), "The platform's prediction at the moment of death");
        long challenge = engine.view().challengeNanos();
        apply(engine, batch(engine, death));
        assertEquals(Map.of(token, new LooseTotem(victim, 1, killer, challenge)), engine.view().looseTotems());
        assertEquals(0, player(engine, killer).revivalCharges());
        assertEquals(ParticipantRole.MONSTER_LOBBY, player(engine, victim).role());
    }

    @Test void aMonsterKillStillPaysOneChargeRecallsTheHunterAndDropsNothing() {
        var engine = fixture(GameMode.HARDCORE, human(killer), human(victim), monster(0));
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        var death = death(engine, victim, monsterHit(), UUID.randomUUID());
        assertFalse(TotemDrop.qualifies(engine.view(), death));
        apply(engine, batch(engine, death));
        assertEquals(1, player(engine, monster).revivalCharges());
        assertEquals(ParticipantRole.MONSTER_LOBBY, player(engine, monster).role(), "revivalFirst recalls the hunter");
        assertTrue(engine.view().looseTotems().isEmpty());
    }

    @Test void mutualKillsAndAKillerWhoAlreadyDiedStillDrop() {
        // Both humans die in one batch, each credited to the other.
        var engine = fixture(GameMode.HARDCORE, human(killer), human(victim), human(bystander));
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        apply(engine, batch(engine, death(engine, victim, humanHit(killer), a), death(engine, killer, humanHit(victim), b)));
        assertEquals(Set.of(a, b), engine.view().looseTotems().keySet());
        assertEquals(killer, engine.view().looseTotems().get(a).killer());
        assertEquals(victim, engine.view().looseTotems().get(b).killer());
        // The archer died earlier; their arrow, launched as a human, lands afterwards.
        var late = fixture(GameMode.HARDCORE, human(killer), human(victim), human(bystander));
        var arrow = origin(killer, ParticipantRole.HUMAN_ACTIVE, 1, 0, AttackKind.ARROW);
        apply(late, batch(late, death(late, killer, null, null)));
        assertEquals(ParticipantRole.MONSTER_LOBBY, player(late, killer).role());
        var token = UUID.randomUUID();
        apply(late, batch(late, death(late, victim, arrow, token)));
        assertEquals(killer, late.view().looseTotems().get(token).killer());
        assertEquals(0, player(late, killer).revivalCharges());
    }

    @Test void selfKillsNormalRoundsAndDeathsWithoutATokenRegisterNothing() {
        var self = fixture(GameMode.HARDCORE, human(killer), human(victim));
        var selfDeath = death(self, victim, humanHit(victim), UUID.randomUUID());
        assertFalse(TotemDrop.qualifies(self.view(), selfDeath));
        apply(self, batch(self, selfDeath));
        assertTrue(self.view().looseTotems().isEmpty());

        var normal = fixture(GameMode.NORMAL, human(killer), human(victim));
        var normalDeath = death(normal, victim, humanHit(killer), UUID.randomUUID());
        assertFalse(TotemDrop.qualifies(normal.view(), normalDeath));
        apply(normal, batch(normal, normalDeath));
        assertTrue(normal.view().looseTotems().isEmpty());

        var untokened = fixture(GameMode.HARDCORE, human(killer), human(victim));
        var plain = death(untokened, victim, humanHit(killer), null);
        assertTrue(TotemDrop.qualifies(untokened.view(), plain), "Qualifies, but the platform dropped nothing");
        apply(untokened, batch(untokened, plain));
        assertTrue(untokened.view().looseTotems().isEmpty());
        assertEquals(0, player(untokened, killer).revivalCharges());
    }

    @Test void aStaleRoundOriginAnOutsiderOrAnIndirectAttackDoesNotQualify() {
        var engine = fixture(GameMode.HARDCORE, human(killer), human(victim));
        var view = engine.view();
        var stale = humanHit(killer);
        stale = new CombatOrigin(UUID.randomUUID(), stale.originId(), stale.actor(), stale.kind(), stale.directEntityId(), List.of());
        for (var credit : java.util.Arrays.asList(null, stale, humanHit(UUID.randomUUID()),
                origin(killer, ParticipantRole.HUMAN_ACTIVE, 1, 0, AttackKind.SUMMON),
                origin(killer, ParticipantRole.HUMAN_ACTIVE, 1, 0, AttackKind.CREEPER),
                origin(killer, ParticipantRole.MONSTER_DEPLOYED, 1, 1, AttackKind.MELEE))) {
            assertFalse(TotemDrop.qualifies(view, death(engine, victim, credit, UUID.randomUUID())), String.valueOf(credit));
        }
        // A monster's body is not a human life.
        var hunting = fixture(GameMode.HARDCORE, human(killer), monster(0));
        apply(hunting, hunting.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        assertFalse(TotemDrop.qualifies(hunting.view(), death(hunting, monster, humanHit(killer), UUID.randomUUID())));
    }

    @Test void aCompletionOrAFailingBatchLeavesNoRegistration() {
        var engine = fixture(GameMode.HARDCORE, human(killer), human(victim));
        var token = UUID.randomUUID();
        var completed = new RoundResult(RoundResult.Kind.COMPLETED, killer, "dragon");
        apply(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(engine, victim, humanHit(killer), token)), completed));
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
        assertTrue(engine.view().looseTotems().isEmpty());

        var failing = fixture(GameMode.HARDCORE, human(killer), human(victim));
        apply(failing, batch(failing, death(failing, victim, humanHit(killer), UUID.randomUUID()),
                death(failing, killer, humanHit(victim), UUID.randomUUID())));
        assertEquals(RoundPhase.SETTLING, failing.view().phase());
        assertEquals(RoundResult.Kind.FAILED, failing.view().result().kind());
        assertTrue(failing.view().looseTotems().isEmpty());
    }

    @Test void anAlreadyRegisteredTokenIsKeptAndNeverFailsTheBatch() {
        var token = UUID.randomUUID();
        var engine = dropped(token);
        var original = engine.view().looseTotems().get(token);
        clock.seconds(3);
        apply(engine, batch(engine, death(engine, bystander, humanHit(killer), token)));
        assertEquals(Map.of(token, original), engine.view().looseTotems());
    }

    // ---- Lifetime and bound (plan 1.2, 2.1) ----

    @Test void anEntryExpiresAfterFiveMinutesOfChallengeTime() {
        var token = UUID.randomUUID();
        var engine = dropped(token);
        clock.seconds(299);
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), false));
        assertTrue(engine.view().looseTotems().containsKey(token), "Still lying there at 4:59");
        assertTrue(engine.view().liveLooseTotem(token).isPresent());
        clock.seconds(1);
        assertTrue(engine.view().liveLooseTotem(token).isEmpty(), "Expired in the projection before any transition");
        assertThrows(IllegalStateException.class,
                () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, bystander, 1));
        assertFalse(engine.pending());
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), false));
        assertTrue(engine.view().looseTotems().isEmpty(), "Any transition forgets it");
    }

    @Test void pausedChallengeTimeDoesNotAgeADrop() {
        var token = UUID.randomUUID();
        var engine = dropped(token);
        for (var id : List.of(killer, bystander)) apply(engine, engine.proposeOnline(UUID.randomUUID(), id, "Human", false));
        clock.seconds(600);
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), false));
        assertTrue(engine.view().looseTotems().containsKey(token), "Nobody was racing, so no time passed");
    }

    @Test void theBoundEvictsTheEarliestDropsLowestTokenFirstAndNeverFails() {
        var totems = new LinkedHashMap<UUID, LooseTotem>();
        for (int i = 0; i < LooseTotem.MAX_REGISTERED; i++) {
            totems.put(new UUID(1, i), new LooseTotem(victim, 1, killer, i < 2 ? 50 : 60));
        }
        var engine = fixture(GameMode.HARDCORE, totems, human(killer), human(victim), human(bystander));
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        apply(engine, batch(engine, death(engine, victim, humanHit(killer), first)));
        var registry = engine.view().looseTotems();
        assertEquals(LooseTotem.MAX_REGISTERED, registry.size());
        assertTrue(registry.containsKey(first));
        assertFalse(registry.containsKey(new UUID(1, 0)), "The earliest drop with the lowest token goes first");
        assertTrue(registry.containsKey(new UUID(1, 1)));
        apply(engine, batch(engine, death(engine, bystander, humanHit(killer), second)));
        assertEquals(LooseTotem.MAX_REGISTERED, engine.view().looseTotems().size());
        assertFalse(engine.view().looseTotems().containsKey(new UUID(1, 1)));
        assertTrue(engine.view().looseTotems().containsKey(second));
        assertEquals(LooseTotem.MAX_REGISTERED, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.view())).looseTotems().size());
    }

    @Test void settlingAbortingAndANewRoundClearTheRegistryWhileRestartsAndRecoveryKeepIt() {
        var token = UUID.randomUUID();
        var finished = dropped(token);
        apply(finished, finished.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.FAILED, null, "test")));
        assertTrue(finished.view().looseTotems().isEmpty());

        var aborted = dropped(token);
        apply(aborted, aborted.proposeAbort(UUID.randomUUID(), "test"));
        assertTrue(aborted.view().looseTotems().isEmpty());

        assertTrue(RoundEngine.create(UUID.randomUUID(), 2, 1, RulesConfig.defaults(), clock).view().looseTotems().isEmpty());

        var restarted = dropped(token);
        apply(restarted, restarted.proposeCheckpoint(UUID.randomUUID(), true));
        var stored = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(restarted.committed()));
        assertTrue(RoundEngine.restore(stored, clock).view().looseTotems().containsKey(token), "Clean restart");
        assertTrue(RoundEngine.restoreVerifiedCheckpoint(stored, clock).view().looseTotems().containsKey(token), "Full checkpoint");
        assertTrue(RoundEngine.restoreForRecovery(stored, clock).view().looseTotems().containsKey(token));

        var rejected = dropped(token);
        assertTrue(rejected.reject(rejected.proposeCheckpoint(UUID.randomUUID(), false), "injected"));
        assertTrue(rejected.view().looseTotems().containsKey(token), "Recovery keeps the durable registry");
    }

    // ---- Pickup (plan 1.3) ----

    @Test void anOnlineHumanOfThisLifeTakesTheTotemOnce() {
        var token = UUID.randomUUID();
        var engine = dropped(token);
        var pickup = engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, bystander, 1);
        assertEquals("TOTEM_PICKUP", pickup.eventType());
        apply(engine, pickup);
        assertEquals(1, player(engine, bystander).revivalCharges());
        assertTrue(engine.view().looseTotems().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, killer, 1), "Claimed already");
        assertEquals(0, player(engine, killer).revivalCharges());
        assertFalse(engine.pending());
    }

    @Test void pickupRejectsStaleLivesRoundsMonstersOfflineHumansUnknownTokensAndPendingWrites() {
        var token = UUID.randomUUID();
        var engine = fixture(GameMode.HARDCORE, human(killer), human(victim), human(bystander), monster(0));
        apply(engine, batch(engine, death(engine, victim, humanHit(killer), token)));
        assertThrows(IllegalStateException.class, () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, killer, 2));
        assertThrows(IllegalStateException.class,
                () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), UUID.randomUUID(), token, killer, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, monster, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, victim, 1),
                "The dead victim is a monster now");
        assertThrows(IllegalStateException.class,
                () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), round, UUID.randomUUID(), killer, 1));
        assertThrows(IllegalStateException.class,
                () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, UUID.randomUUID(), 1));
        apply(engine, engine.proposeOnline(UUID.randomUUID(), bystander, "Human", false));
        assertThrows(IllegalStateException.class, () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, bystander, 1));
        var first = engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, killer, 1);
        assertThrows(IllegalStateException.class, () -> engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, killer, 1),
                "A second claim while the first is being written");
        apply(engine, first);
        assertEquals(1, player(engine, killer).revivalCharges());
        assertEquals(0, player(engine, bystander).revivalCharges());
        assertTrue(engine.view().looseTotems().isEmpty());
    }

    @Test void pickupAtTheChargeLimitStillTakesTheTotem() {
        var token = UUID.randomUUID();
        var engine = fixture(GameMode.HARDCORE, human(killer).addRevivalCharges(Integer.MAX_VALUE), human(victim), human(bystander));
        apply(engine, batch(engine, death(engine, victim, humanHit(bystander), token)));
        apply(engine, engine.proposeLooseTotemPickup(UUID.randomUUID(), round, token, killer, 1));
        assertEquals(Integer.MAX_VALUE, player(engine, killer).revivalCharges());
        assertTrue(engine.view().looseTotems().isEmpty());
    }

    @Test void pickupIsClosedInNormalRoundsAndAfterTheRoundSettled() {
        var token = UUID.randomUUID();
        var normal = fixture(GameMode.NORMAL, Map.of(token, new LooseTotem(victim, 1, killer, 100)), human(killer), human(victim));
        assertThrows(IllegalStateException.class, () -> normal.proposeLooseTotemPickup(UUID.randomUUID(), round, token, killer, 1));
        var settled = dropped(token);
        apply(settled, settled.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.FAILED, null, "test")));
        assertThrows(IllegalStateException.class, () -> settled.proposeLooseTotemPickup(UUID.randomUUID(), round, token, killer, 1));
    }

    // ---- In-place save (plan 1.5) ----

    @Test void aSaveSpendsOneChargeAndKeepsTheLife() {
        var engine = fixture(GameMode.HARDCORE, human(killer).addRevivalCharges(2), human(victim));
        var save = engine.proposeTotemSave(UUID.randomUUID(), round, killer, 1, Optional.empty());
        assertEquals("TOTEM_SAVE", save.eventType());
        apply(engine, save);
        var saved = player(engine, killer);
        assertEquals(1, saved.revivalCharges());
        assertEquals(ParticipantRole.HUMAN_ACTIVE, saved.role());
        assertEquals(1, saved.lifeSequence(), "Not a death");
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        assertTrue(engine.view().looseTotems().isEmpty(), "A save drops nothing");
    }

    @Test void aMonsterForcingOutATotemIsPaidAndRecalledLikeAKill() {
        var engine = fixture(GameMode.HARDCORE, human(killer).addRevivalCharges(1), human(victim), monster(0));
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        assertEquals(Optional.of(monster), RoundEngine.monsterKiller(engine.view(), killer, Optional.of(monsterHit())));
        apply(engine, engine.proposeTotemSave(UUID.randomUUID(), round, killer, 1, Optional.of(monsterHit())));
        assertEquals(0, player(engine, killer).revivalCharges());
        assertEquals(ParticipantRole.HUMAN_ACTIVE, player(engine, killer).role());
        assertEquals(1, player(engine, monster).revivalCharges());
        assertEquals(ParticipantRole.MONSTER_LOBBY, player(engine, monster).role(), "revivalFirst recalls the hunter");
    }

    @Test void aSaveForcedOutByAnUnqualifiedMonsterAttackPaysNoOne() {
        var engine = fixture(GameMode.HARDCORE, human(killer).addRevivalCharges(1), human(victim), monster(0));
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        // A later sortie than the monster ever had, and a non-monster attack kind.
        for (var credit : List.of(origin(monster, ParticipantRole.MONSTER_DEPLOYED, 1, 9, AttackKind.MELEE),
                origin(monster, ParticipantRole.MONSTER_DEPLOYED, 1, 4, AttackKind.BED))) {
            assertEquals(Optional.empty(), RoundEngine.monsterKiller(engine.view(), killer, Optional.of(credit)));
        }
        apply(engine, engine.proposeTotemSave(UUID.randomUUID(), round, killer, 1,
                Optional.of(origin(monster, ParticipantRole.MONSTER_DEPLOYED, 1, 4, AttackKind.BED))));
        assertEquals(0, player(engine, monster).revivalCharges());
        assertEquals(ParticipantRole.MONSTER_DEPLOYED, player(engine, monster).role());
    }

    @Test void aHumanForcingOutATotemEarnsNothingAndDropsNothing() {
        var engine = fixture(GameMode.HARDCORE, human(killer), human(victim).addRevivalCharges(1));
        apply(engine, engine.proposeTotemSave(UUID.randomUUID(), round, victim, 1, Optional.of(humanHit(killer))));
        assertEquals(0, player(engine, victim).revivalCharges());
        assertEquals(0, player(engine, killer).revivalCharges());
        assertTrue(engine.view().looseTotems().isEmpty());
    }

    @Test void staleOrUnqualifiedSavesCommitAVoidThatChangesNoParticipantAndNeverThrow() {
        // No charge, a changed life, another round, a missing player, a monster, a normal round.
        var engine = fixture(GameMode.HARDCORE, human(killer), human(victim).addRevivalCharges(1), monster(0));
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        var credit = Optional.of(monsterHit());
        assertVoid(engine, round, killer, 1, credit);
        assertVoid(engine, round, victim, 2, credit);
        assertVoid(engine, UUID.randomUUID(), victim, 1, credit);
        assertVoid(engine, round, UUID.randomUUID(), 1, credit);
        assertVoid(engine, round, monster, 1, credit);
        var normal = fixture(GameMode.NORMAL, human(killer).addRevivalCharges(1), human(victim));
        assertVoid(normal, round, killer, 1, Optional.empty());
        // The saved human died after the save was captured.
        apply(engine, batch(engine, death(engine, victim, monsterHit(), null)));
        assertVoid(engine, round, victim, 1, credit);
        assertEquals(1, player(engine, monster).revivalCharges(), "Only the actual death paid the monster");
    }

    @Test void savesOutsideAnOpenRaceCommitAVoid() {
        var settled = fixture(GameMode.HARDCORE, human(killer).addRevivalCharges(1), human(victim));
        apply(settled, settled.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.FAILED, null, "test")));
        assertVoid(settled, round, killer, 1, Optional.empty());
        clock.seconds(3_600);
        apply(settled, settled.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.ROTATING, settled.view().phase());
        assertVoid(settled, round, killer, 1, Optional.empty());

        var recovery = fixture(GameMode.HARDCORE, human(killer).addRevivalCharges(1), human(victim));
        assertTrue(recovery.reject(recovery.proposeCheckpoint(UUID.randomUUID(), false), "injected"));
        assertEquals(RoundPhase.RECOVERY_REQUIRED, recovery.view().phase());
        assertVoid(recovery, round, killer, 1, Optional.empty());

        var waiting = RoundEngine.create(round, 1, 1, RulesConfig.defaults().withMode(GameMode.HARDCORE), clock);
        assertVoid(waiting, round, killer, 1, Optional.empty());

    }

    @Test void anExpiredRescueVoidsTheSave() {
        var engine = fixture(GameMode.HARDCORE, human(victim), human(killer), monster(1));
        // Both humans go offline: the round waits for a reconnect on a deadline.
        apply(engine, engine.proposeOnline(UUID.randomUUID(), victim, "Human", false));
        apply(engine, engine.proposeOnline(UUID.randomUUID(), killer, "Human", false));
        assertEquals(RoundPhase.RESCUE, engine.view().phase());
        clock.seconds(RoundEngine.reconnectMillis(engine.view().rules()) / 1000);
        assertEquals(0, engine.view().remainingPhaseNanos());
        assertVoid(engine, round, victim, 1, Optional.empty());
    }

    private void assertVoid(RoundEngine engine, UUID roundId, UUID playerId, int life, Optional<CombatOrigin> credit) {
        var before = engine.view();
        var transition = assertDoesNotThrow(() -> engine.proposeTotemSave(UUID.randomUUID(), roundId, playerId, life, credit));
        assertEquals("TOTEM_SAVE_VOID", transition.eventType());
        assertEquals(before.participants(), transition.after().participants());
        apply(engine, transition);
    }
}
