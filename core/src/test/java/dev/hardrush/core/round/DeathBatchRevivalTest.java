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
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeathBatchRevivalTest {
    private final UUID round = UUID.randomUUID();
    private final UUID human = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();
    private final UUID batch = UUID.randomUUID();
    private final Clock clock = new Clock();
    private static final class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
        void seconds(long value) { now += value * 1_000_000_000L; }
    }
    private static void apply(RoundEngine engine, RoundTransition event) { assertTrue(engine.accept(event)); }
    private Participant human(UUID id, boolean online) {
        return new Participant(id, "Human", ParticipantRole.HUMAN_ACTIVE, online, false, 100, 50, 1, false, true);
    }
    private Participant monster(boolean online, boolean entitled) {
        return new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, online, false,
                70, 20, 1, entitled, true, MonsterRole.CREEPER, MonsterDimension.NETHER,
                0, 3, Map.of(MonsterSkill.ZOMBIE_SUMMON, 80_000L));
    }
    private RoundEngine fixture(GameMode mode, Participant... participants) {
        Map<UUID, Participant> players = new LinkedHashMap<>();
        for (var p : participants) players.put(p.id(), p);
        var stored = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults().withMode(mode),
                RoundPhase.RUNNING, PauseReason.SERVER_STOPPED, 100, 0, players, true, false, null);
        var engine = RoundEngine.restore(stored, clock);
        for (var p : participants) if (p.online()) apply(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        return engine;
    }
    private CombatOrigin origin(UUID owner, ParticipantRole role, AttackKind kind) {
        return new CombatOrigin(round, UUID.randomUUID(), new CombatActor(owner, role, 1, role.isMonster() ? 3 : 0),
                kind, kind == AttackKind.ARROW ? Optional.of(UUID.randomUUID()) : Optional.empty(), List.of());
    }
    private CombatEvent lethal(UUID victim, CombatOrigin origin) {
        var source = Optional.ofNullable(origin);
        return new CombatEvent(round, UUID.randomUUID(), batch, 1, victim, source,
                CombatEvent.classify(round, source), "test:actual_lethal", Optional.empty(), Optional.empty(), 20, true, clock.now);
    }
    private RoundDeath death(RoundEngine engine, UUID victim, CombatOrigin origin) {
        var p = engine.view().participants().get(victim);
        return new RoundDeath(victim, p.lifeSequence(), p.monsterDeploymentSequence(), Optional.of(lethal(victim, origin)));
    }
    private RoundTransition batch(RoundEngine engine, RoundDeath... deaths) {
        return engine.proposeDeathBatch(UUID.randomUUID(), round, List.of(deaths), null);
    }
    private RoundEngine rescue(boolean online) {
        var engine = fixture(GameMode.HARDCORE, human(human, true), monster(online, false));
        apply(engine, batch(engine, death(engine, human, origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.SUMMON))));
        assertEquals(RoundPhase.RESCUE, engine.view().phase());
        return engine;
    }

    @Test void adminCreditsStackConsumeOneAtATimeAndSurviveHumanDeathsAndCleanRestarts() {
        var engine = fixture(GameMode.HARDCORE, human(human, true), monster(true, true));
        apply(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 1, 2));
        assertEquals(3, engine.view().participants().get(monster).revivalCharges());
        assertTrue(engine.view().timingUncertain());
        var cooldowns = engine.view().participants().get(monster).monsterSkillReadyAtMillis();
        for (int remaining = 2; remaining >= 0; remaining--) {
            var before = engine.view().participants().get(monster);
            apply(engine, engine.proposeRevival(UUID.randomUUID(), round, monster, before.lifeSequence(), 3));
            assertEquals(remaining, engine.view().participants().get(monster).revivalCharges());
            assertEquals(cooldowns, engine.view().participants().get(monster).monsterSkillReadyAtMillis());
            apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
            engine = RoundEngine.restore(RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed())), clock);
            apply(engine, engine.proposeOnline(UUID.randomUUID(), monster, "Monster", true));
            apply(engine, batch(engine, death(engine, monster, null)));
            assertEquals(remaining, engine.view().participants().get(monster).revivalCharges());
        }
        var exhausted = engine;
        int life = engine.view().participants().get(monster).lifeSequence();
        assertThrows(IllegalStateException.class, () -> exhausted.proposeRevival(UUID.randomUUID(), round, monster, life, 3));
    }

    @Test void adminCanPregrantOfflineHumanAndKillRewardDoesNotIncreaseExistingCredits() {
        var engine = fixture(GameMode.HARDCORE, human(human, false), human(other, true), monster(true, false));
        apply(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, human, 1, 0, 3));
        apply(engine, engine.proposeHardcoreDeath(UUID.randomUUID(), human, 1));
        assertEquals(3, engine.view().participants().get(human).revivalCharges());
        apply(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 0, 4));
        apply(engine, batch(engine, death(engine, other, origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.SUMMON))));
        assertEquals(4, engine.view().participants().get(monster).revivalCharges());
        assertEquals(3, engine.view().participants().get(human).revivalCharges());
        apply(engine, engine.proposeAbort(UUID.randomUUID(), "end test"));
        assertTrue(engine.view().participants().values().stream().allMatch(p -> p.revivalCharges() == 0));
    }

    @Test void adminGrantRejectsStaleIdentityNonpositiveCountsOverflowAndWrongModeWithoutPendingMutation() {
        var engine = fixture(GameMode.HARDCORE, human(human, true), monster(true, false));
        assertThrows(IllegalArgumentException.class, () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 0, -1));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), UUID.randomUUID(), monster, 1, 0, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 2, 0, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 1, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), round, UUID.randomUUID(), 1, 0, 1));
        assertFalse(engine.pending());
        apply(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 0, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, Integer.MAX_VALUE, 1));
        assertFalse(engine.pending());
        var normal = fixture(GameMode.NORMAL, human(human, true));
        assertThrows(IllegalStateException.class, () -> normal.proposeAdminGrantRevival(UUID.randomUUID(), round, human, 1, 0, 1));
        assertFalse(normal.pending());
    }

    @Test void adminGrantDoesNotExtendRescueAndCannotReopenExpiredOrCompletedRounds() {
        var engine = rescue(true);
        clock.seconds(21);
        apply(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 1, 3));
        assertEquals(39_000, engine.view().remainingPhaseMillis());
        assertEquals(4, engine.view().participants().get(monster).revivalCharges());
        clock.seconds(39);
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 4, 1));
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(0, engine.view().participants().get(monster).revivalCharges());
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 0, 1));
    }

    @Test void selfExplosionMultikillAwardsBeforeTestingLastHumanAndKeepsOneEntitlement() {
        var engine = fixture(GameMode.HARDCORE, human(human, true), human(other, true), monster(true, false));
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        var explosion = origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.CREEPER);
        clock.seconds(2);
        var humanDeath = death(engine, human, explosion);
        var transition = batch(engine, humanDeath, death(engine, other, explosion), death(engine, monster, null));
        assertFalse(engine.view().participants().get(monster).revivalEntitled());
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        apply(engine, transition);
        assertEquals(RoundPhase.RESCUE, engine.view().phase());
        assertEquals(60_000, engine.view().remainingPhaseMillis());
        var rewarded = engine.view().participants().get(monster);
        assertTrue(rewarded.revivalEntitled());
        assertEquals(ParticipantRole.MONSTER_LOBBY, rewarded.role());
        assertEquals(30_000_000_000L, rewarded.monsterCooldownNanos());
        assertTrue(engine.view().participants().get(human).role().isMonster());
        assertTrue(engine.view().participants().get(other).role().isMonster());
        assertThrows(IllegalStateException.class, () -> batch(engine, humanDeath));
    }

    @Test void offlineLobbySummonOwnerEarnsAndOfflineHumanPreventsAnyRescue() {
        var engine = fixture(GameMode.HARDCORE, human(human, true), human(other, false), monster(false, false));
        apply(engine, batch(engine, death(engine, human, origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.SUMMON))));
        assertTrue(engine.view().participants().get(monster).revivalEntitled());
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        assertEquals(PauseReason.NO_ONLINE_HUMANS, engine.view().pauseReason());
        clock.seconds(500);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        assertNull(engine.view().result());
    }

    @Test void noHumanAndNoEntitlementFailsWhileNormalDeathNeverUsesHardcoreSettlement() {
        var engine = fixture(GameMode.HARDCORE, human(human, true));
        apply(engine, batch(engine, death(engine, human, null)));
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
        assertEquals("ALL_HUMANS_DEAD", engine.view().result().reason());
        assertFailureDisplayUntilRotation(engine);
        var normal = fixture(GameMode.NORMAL, human(human, true), monster(true, false));
        apply(normal, batch(normal, death(normal, human, origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.MELEE))));
        assertEquals(RoundPhase.RUNNING, normal.view().phase());
        assertEquals(ParticipantRole.HUMAN_RESPAWNING, normal.view().participants().get(human).role());
        assertFalse(normal.view().participants().get(monster).revivalEntitled());
        assertNull(normal.view().result());
    }

    @Test void oldHumanArrowAndRevivedOwnerMonsterArrowCannotBankEntitlements() {
        var engine = fixture(GameMode.HARDCORE, human(human, true), monster(true, false));
        apply(engine, batch(engine, death(engine, human, origin(monster, ParticipantRole.HUMAN_ACTIVE, AttackKind.ARROW))));
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
        assertFalse(engine.view().participants().get(monster).revivalEntitled());
        var revivedOwner = fixture(GameMode.HARDCORE, human(human, true), human(monster, true));
        apply(revivedOwner, batch(revivedOwner, death(revivedOwner, human,
                origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.ARROW)), death(revivedOwner, monster, null)));
        assertEquals(RoundPhase.SETTLING, revivedOwner.view().phase());
        assertFalse(revivedOwner.view().participants().get(monster).revivalEntitled());
    }

    @Test void staleRoundOriginUnsupportedAttackAndUnadmittedOwnerNeverEarn() {
        var stale = origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.ARROW);
        stale = new CombatOrigin(UUID.randomUUID(), stale.originId(), stale.actor(), stale.kind(), stale.directEntityId(), List.of());
        for (var source : List.of(stale, origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.BED),
                origin(UUID.randomUUID(), ParticipantRole.MONSTER_DEPLOYED, AttackKind.SUMMON))) {
            var engine = fixture(GameMode.HARDCORE, human(human, true), monster(true, false));
            apply(engine, batch(engine, death(engine, human, source)));
            assertEquals(RoundPhase.SETTLING, engine.view().phase());
            assertFalse(engine.view().participants().get(monster).revivalEntitled());
        }
    }

    @Test void cooldownLobbyRevivalIsAtomicFreshProtectedLifeAndRetainsProgressAndSkillDeadlines() {
        var engine = rescue(true);
        var before = engine.view().participants().get(monster);
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        assertThrows(IllegalStateException.class, () -> engine.proposeRevival(UUID.randomUUID(), round, monster, 1, 4));
        apply(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 4));
        assertEquals(30_000_000_000L, engine.view().participants().get(monster).monsterCooldownNanos());
        var pending = engine.proposeAdmissionRevival(UUID.randomUUID(), round, monster, 1, 4);
        assertEquals(ParticipantRole.MONSTER_LOBBY, engine.view().participants().get(monster).role());
        assertTrue(engine.view().participants().get(monster).revivalEntitled());
        assertThrows(IllegalStateException.class, () -> engine.proposeAdmissionRevival(UUID.randomUUID(), round, monster, 1, 4));
        clock.seconds(75);
        assertThrows(IllegalStateException.class, () -> engine.proposeTick(UUID.randomUUID()));
        apply(engine, pending);
        var revived = engine.view().participants().get(monster);
        assertEquals(ParticipantRole.HUMAN_ACTIVE, revived.role());
        assertEquals(2, revived.lifeSequence());
        assertFalse(revived.online());
        assertFalse(revived.revivalEntitled());
        assertFalse(revived.hasLeftProtection());
        assertEquals(before.humanNanos(), revived.humanNanos());
        assertEquals(before.joinedAtChallengeNanos(), revived.joinedAtChallengeNanos());
        assertEquals(before.monsterSkillReadyAtMillis(), revived.monsterSkillReadyAtMillis());
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        assertThrows(IllegalStateException.class, () -> engine.proposeRevival(UUID.randomUUID(), round, monster, 1, 4));
    }

    @Test void deadlineRejectsNewRequestsAndExpiryInvalidatesEvenOfflineEntitlement() {
        for (boolean online : List.of(true, false)) {
            var engine = rescue(online);
            clock.seconds(60);
            assertThrows(IllegalStateException.class, () -> engine.proposeJoin(UUID.randomUUID(), other, "TooLate"));
            assertThrows(IllegalStateException.class, () -> engine.proposeRevival(UUID.randomUUID(), round, monster, 1, 3));
            apply(engine, engine.proposeTick(UUID.randomUUID()));
            assertEquals(RoundPhase.SETTLING, engine.view().phase());
            assertEquals("RESCUE_EXPIRED", engine.view().result().reason());
            assertFalse(engine.view().participants().get(monster).revivalEntitled());
            assertThrows(IllegalStateException.class, () -> engine.proposeRevival(UUID.randomUUID(), round, monster, 1, 3));
            assertFailureDisplayUntilRotation(engine);
        }
    }

    private void assertFailureDisplayUntilRotation(RoundEngine engine) {
        var result = engine.view().result();
        assertEquals(RoundResult.Kind.FAILED, result.kind());
        long challenge = engine.view().challengeNanos();
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
        assertEquals(30_000, engine.view().remainingPhaseMillis());
        clock.seconds(29);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
        assertEquals(1_000, engine.view().remainingPhaseMillis());
        var tab = dev.hardrush.core.record.RoundTab.from(engine.view(), 0, null);
        assertTrue(tab.header().contains("挑战失败"));
        assertFalse(tab.header().contains("庆祝"));
        clock.seconds(1);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.ROTATING, engine.view().phase());
        assertEquals(result, engine.view().result());
        assertEquals(challenge, engine.view().challengeNanos());
    }

    @Test void beforeDeadlineAcceptedRevivalWinsAndLaterTickCannotRaceThePendingWrite() {
        var engine = rescue(true);
        clock.seconds(59);
        var revival = engine.proposeAdmissionRevival(UUID.randomUUID(), round, monster, 1, 3);
        clock.seconds(5);
        assertThrows(IllegalStateException.class, () -> engine.proposeTick(UUID.randomUUID()));
        apply(engine, revival);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        assertNull(engine.view().result());
        assertEquals(PauseReason.NO_ONLINE_HUMANS, engine.view().pauseReason());
    }

    @Test void pendingCheckpointCannotFreezeTheLiveRescueAdmissionDeadline() {
        var engine = rescue(true);
        clock.seconds(59);
        var checkpoint = engine.proposeCheckpoint(UUID.randomUUID(), false);
        assertFalse(engine.rescueExpiredAt(clock.now));
        assertFalse(engine.rescueExpiredAt(clock.now - 1));
        clock.seconds(1);
        assertEquals(1_000, engine.view().remainingPhaseMillis(), "The public pending view remains frozen");
        assertTrue(engine.rescueExpiredAt(clock.now), "Native admission independently closes at exactly 60 seconds");
        clock.seconds(2);
        assertTrue(engine.rescueExpiredAt(clock.now));
        apply(engine, checkpoint);
        assertTrue(engine.rescueExpiredAt(clock.now));
        var expiry = engine.proposeTick(UUID.randomUUID());
        assertTrue(engine.rescueExpiredAt(clock.now), "The pending expiry write must keep late damage sealed");
        apply(engine, expiry);
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
    }

    @Test void acceptedRevivalCancelsLiveRescueDeadlineThroughoutItsPendingWrite() {
        var engine = rescue(true);
        clock.seconds(59);
        var revival = engine.proposeAdmissionRevival(UUID.randomUUID(), round, monster, 1, 3);
        clock.seconds(5);
        assertEquals(RoundPhase.RESCUE, engine.view().phase(), "The public pending view still shows the prior identity");
        assertFalse(engine.rescueExpiredAt(clock.now), "Already accepted revival takes priority over later expiry");
        apply(engine, revival);
        assertFalse(engine.rescueExpiredAt(clock.now));
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
    }

    @Test void completionAcceptedBeforeRescueDeadlineWinsButAnUnacceptedLateDragonCannot() {
        var timely = rescue(true);
        clock.seconds(59);
        var completed = new RoundResult(RoundResult.Kind.COMPLETED, human, "FIRST_DRAGON_HUMAN_FINAL_HIT");
        var terminal = timely.proposeDeathBatch(UUID.randomUUID(), round, List.of(), completed);
        clock.seconds(3);
        apply(timely, terminal);
        assertEquals(completed, timely.view().result());

        var late = rescue(true);
        clock.seconds(60);
        assertTrue(late.rescueExpiredAt(clock.now));
        assertThrows(IllegalStateException.class, () -> late.proposeDeathBatch(UUID.randomUUID(), round, List.of(), completed));
        assertFalse(late.pending());
        apply(late, late.proposeTick(UUID.randomUUID()));
        assertEquals(RoundResult.Kind.FAILED, late.view().result().kind());
        assertEquals("RESCUE_EXPIRED", late.view().result().reason());
    }

    @Test void newHumanCancelsRescueAndAnotherWipeStartsANewWindow() {
        var engine = rescue(true);
        clock.seconds(59);
        apply(engine, engine.proposeAdmissionJoin(UUID.randomUUID(), other, "Rescuer"));
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        apply(engine, engine.proposeOnline(UUID.randomUUID(), other, "Rescuer", true));
        clock.seconds(4);
        apply(engine, batch(engine, death(engine, other, null)));
        assertEquals(RoundPhase.RESCUE, engine.view().phase());
        assertEquals(60_000, engine.view().remainingPhaseMillis());
    }

    @Test void reconnectMoreMonsterDeathsAndRoleSelectionNeverExtendRescue() {
        var engine = rescue(true);
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        clock.seconds(20);
        apply(engine, batch(engine, death(engine, monster, null)));
        assertTrue(engine.view().participants().get(monster).revivalEntitled());
        assertEquals(40_000, engine.view().remainingPhaseMillis());
        apply(engine, engine.proposeOnline(UUID.randomUUID(), monster, "Offline", false));
        clock.seconds(10);
        apply(engine, engine.proposeOnline(UUID.randomUUID(), monster, "Online", true));
        apply(engine, engine.proposeMonsterRole(UUID.randomUUID(), round, monster, 1, 4,
                MonsterRole.SKELETON));
        assertEquals(30_000, engine.view().remainingPhaseMillis());
        assertEquals(30_000_000_000L, engine.view().participants().get(monster).monsterCooldownNanos());
        clock.seconds(5);
        assertEquals(25_000_000_000L, engine.view().participants().get(monster).monsterCooldownNanos());
    }

    @Test void rescueCleanRestartFreezesRemainingAndChallengeTimeWhilePreservingQualifications() {
        var engine = rescue(true);
        clock.seconds(21);
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        var saved = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed()));
        clock.seconds(100_000);
        var reopened = RoundEngine.restore(saved, clock);
        assertEquals(RoundPhase.RESCUE, reopened.view().phase());
        assertEquals(39_000, reopened.view().remainingPhaseMillis());
        assertTrue(reopened.view().participants().get(monster).revivalEntitled());
        apply(reopened, reopened.proposeOnline(UUID.randomUUID(), monster, "Restored", true));
        clock.seconds(3);
        assertEquals(36_000, reopened.view().remainingPhaseMillis());
        assertEquals(engine.committed().challengeNanos(), reopened.view().challengeNanos());
    }

    @Test void dragonCompletionWinsTheSameBatchAsLastHumanDeathAndClearsRewards() {
        var engine = fixture(GameMode.HARDCORE, human(human, true), monster(true, false));
        var completion = new RoundResult(RoundResult.Kind.COMPLETED, human, "FIRST_DRAGON_HUMAN_FINAL_HIT");
        var transition = engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(engine, human, origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.SUMMON))), completion);
        assertEquals("DEATH_BATCH", transition.eventType());
        apply(engine, transition);
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
        assertEquals(completion, engine.view().result());
        assertEquals(180_000, engine.view().remainingPhaseMillis());
        assertTrue(engine.view().participants().get(human).role().isMonster());
        assertFalse(engine.view().participants().get(monster).revivalEntitled());
    }

    @Test void malformedBatchAndStaleLifeNeverLockEngineOrGrantPartialRewards() {
        var engine = fixture(GameMode.HARDCORE, human(human, true), monster(true, false));
        var valid = death(engine, human, origin(monster, ParticipantRole.MONSTER_DEPLOYED, AttackKind.MELEE));
        assertThrows(IllegalArgumentException.class, () -> engine.proposeDeathBatch(UUID.randomUUID(), round, List.of(valid, valid), null));
        assertThrows(IllegalStateException.class, () -> engine.proposeDeathBatch(UUID.randomUUID(), UUID.randomUUID(), List.of(valid), null));
        assertThrows(IllegalStateException.class, () -> batch(engine, new RoundDeath(human, 2, 0, valid.cause())));
        assertFalse(engine.pending());
        assertFalse(engine.view().participants().get(monster).revivalEntitled());
        assertEquals(ParticipantRole.HUMAN_ACTIVE, engine.view().participants().get(human).role());
    }

    @Test void failedRevivalWriteRetainsQualificationAndRequiresRecovery() {
        var engine = rescue(true);
        var revival = engine.proposeAdmissionRevival(UUID.randomUUID(), round, monster, 1, 3);
        assertTrue(engine.reject(revival, "injected SQLite failure"));
        assertEquals(RoundPhase.RECOVERY_REQUIRED, engine.view().phase());
        assertTrue(engine.committed().participants().get(monster).revivalEntitled());
        assertEquals(ParticipantRole.MONSTER_LOBBY, engine.committed().participants().get(monster).role());
        assertEquals(1, engine.committed().participants().get(monster).lifeSequence());
        assertFalse(engine.accept(revival));
    }
}
