package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.hunt.LateShelter;
import dev.hardrush.core.hunt.PersonalHuntRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** docs/plan/管理员命令与面板.md: the engine side of the administrator's adjustments. */
class AdminAdjustmentTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    private static final long SECOND = 1_000_000_000L;
    private final UUID round = UUID.randomUUID();
    private final UUID human = UUID.randomUUID();
    private final UUID late = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();
    private final long[] now = {0};

    @Test void revivalChargesCanBeGivenAndTakenButNeverBelowZero() {
        var engine = live(0);
        accept(engine, engine.proposeAdminRevival(UUID.randomUUID(), round, human, 1, 0, 3));
        assertEquals(3, engine.view().participants().get(human).revivalCharges());
        assertTrue(engine.view().timingUncertain());
        var take = engine.proposeAdminRevival(UUID.randomUUID(), round, human, 1, 3, -2);
        assertEquals("ADMIN_TAKE_REVIVAL", take.eventType());
        accept(engine, take);
        assertEquals(1, engine.view().participants().get(human).revivalCharges());
        assertThrows(IllegalArgumentException.class, () -> engine.proposeAdminRevival(UUID.randomUUID(), round, human, 1, 1, -2));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminRevival(UUID.randomUUID(), round, human, 1, 0, -1),
                "A stale balance is refused");
        assertThrows(IllegalArgumentException.class, () -> engine.proposeAdminRevival(UUID.randomUUID(), round, human, 1, 1, 0));
        assertFalse(engine.pending());
    }

    @Test void normalRoundsHaveNoTotems() {
        var engine = live(0, RULES.withMode(GameMode.NORMAL));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminRevival(UUID.randomUUID(), round, human, 1, 0, 1));
    }

    @Test void takingTheLastRevivalARescueWaitsForFailsTheRound() {
        var players = new LinkedHashMap<UUID, Participant>();
        players.put(monster, camp(20).addRevivalCharges(1));
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RESCUE, PauseReason.SERVER_STOPPED,
                100, 30 * SECOND, players, true, false, null), () -> now[0]);
        accept(engine, engine.proposeOnline(UUID.randomUUID(), monster, "Monster", true));
        assertEquals(RoundPhase.RESCUE, engine.view().phase());
        accept(engine, engine.proposeAdminRevival(UUID.randomUUID(), round, monster, 1, 1, -1));
        assertEquals(RoundPhase.SETTLING, engine.view().phase());
        assertEquals(RoundResult.Kind.FAILED, engine.view().result().kind());
    }

    @Test void coinsCanBeGivenAndTakenOnlyFromMonsters() {
        var engine = live(10);
        accept(engine, engine.proposeAdminCoins(UUID.randomUUID(), round, monster, 1, 10, 5));
        assertEquals(15, engine.view().participants().get(monster).wallet().coins());
        accept(engine, engine.proposeAdminCoins(UUID.randomUUID(), round, monster, 1, 15, -15));
        assertEquals(0, engine.view().participants().get(monster).wallet().coins());
        assertTrue(engine.view().timingUncertain());
        assertThrows(IllegalArgumentException.class, () -> engine.proposeAdminCoins(UUID.randomUUID(), round, monster, 1, 0, -1));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminCoins(UUID.randomUUID(), round, human, 1, 0, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminCoins(UUID.randomUUID(), round, monster, 1, 7, 1));
    }

    @Test void deployCooldownAndDefeatLocksCanBeCleared() {
        var players = players(camp(0).withMonsterCooldown(45 * SECOND).withHuntLock(human, 45 * SECOND).withHuntLock(late, 45 * SECOND));
        var engine = online(players, RULES);
        var refused = assertThrows(Refusal.Exception.class, () -> engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 2, MonsterRole.ZOMBIE, human, 1), 2));
        assertEquals(Refusal.HUNT_LOCKED, refused.refusal());
        accept(engine, engine.proposeAdminDeployCooldown(UUID.randomUUID(), round, monster, 1, 0));
        assertEquals(0, engine.view().participants().get(monster).monsterCooldownNanos());
        accept(engine, engine.proposeAdminHuntUnlock(UUID.randomUUID(), round, monster, human));
        assertEquals(Map.of(late, 45 * SECOND), engine.view().participants().get(monster).huntLocks());
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminHuntUnlock(UUID.randomUUID(), round, monster, human),
                "Nothing left to lift for that human");
        accept(engine, engine.proposeAdminHuntUnlock(UUID.randomUUID(), round, monster, null));
        assertTrue(engine.view().participants().get(monster).huntLocks().isEmpty());
        accept(engine, engine.proposeAdminDeployCooldown(UUID.randomUUID(), round, monster, 1, 10 * SECOND));
        assertEquals(10 * SECOND, engine.view().participants().get(monster).monsterCooldownNanos());
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminDeployCooldown(UUID.randomUUID(), round, human, 1, 0));
    }

    @Test void endingAMidRoundEntrantsShelterLetsMonstersChooseItAndSurvivesTheCodec() {
        var engine = live(0);
        var sheltered = engine.view().participants().get(late);
        assertTrue(LateShelter.sheltered(RULES, sheltered));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminEndShelter(UUID.randomUUID(), round, monster, 1),
                "Only a human life is sheltered");
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminEndShelter(UUID.randomUUID(), round, late, 2),
                "A stale life is refused");
        accept(engine, engine.proposeAdminEndShelter(UUID.randomUUID(), round, late, 1));
        var waived = engine.view().participants().get(late);
        assertFalse(LateShelter.sheltered(RULES, waived));
        assertTrue(engine.view().timingUncertain());
        var decoded = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed()));
        assertTrue(decoded.participants().get(late).shelterWaived());
        assertFalse(decoded.participants().get(human).shelterWaived());
        assertEquals(engine.committed(), decoded);
    }

    @Test void phaseTimerOnlyChangesTimedPhasesAndOnlyARescueMarksTheRecord() {
        var engine = live(0);
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminPhaseTimer(UUID.randomUUID(), round, 0),
                "A running race has no phase timer");
        var players = new LinkedHashMap<UUID, Participant>();
        players.put(monster, camp(0).addRevivalCharges(1));
        var rescue = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RESCUE, PauseReason.SERVER_STOPPED,
                100, 30 * SECOND, players, true, false, null), () -> now[0]);
        accept(rescue, rescue.proposeOnline(UUID.randomUUID(), monster, "Monster", true));
        accept(rescue, rescue.proposeAdminPhaseTimer(UUID.randomUUID(), round, 5 * SECOND));
        assertEquals(5_000, rescue.view().remainingPhaseMillis());
        assertTrue(rescue.view().timingUncertain());
        accept(rescue, rescue.proposeAdminPhaseTimer(UUID.randomUUID(), round, 0));
        accept(rescue, rescue.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.SETTLING, rescue.view().phase());
        // The result display can be cut short too.
        accept(rescue, rescue.proposeAdminPhaseTimer(UUID.randomUUID(), round, 0));
        accept(rescue, rescue.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.ROTATING, rescue.view().phase());
    }

    @Test void countdownTimerAndUntrustedFlagStayClean() {
        var engine = RoundEngine.create(round, 1, 2, RULES, () -> now[0]);
        accept(engine, engine.proposeWorldReady(UUID.randomUUID()));
        accept(engine, engine.proposeJoin(UUID.randomUUID(), human, "Human"));
        accept(engine, engine.proposeReady(UUID.randomUUID(), human, true));
        accept(engine, engine.proposeAdminPhaseTimer(UUID.randomUUID(), round, 0));
        assertFalse(engine.view().timingUncertain());
        accept(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
    }

    @Test void pauseFreezesRaceTimersAndBlocksSortiesUntilResumed() {
        var players = players(camp(0).withMonsterCooldown(45 * SECOND).withHuntLock(human, 45 * SECOND));
        var engine = online(players, RULES);
        now[0] = 10 * SECOND;
        long challenge = engine.view().challengeNanos();
        var pause = engine.proposeAdminPause(UUID.randomUUID(), round, true);
        accept(engine, pause);
        assertTrue(engine.view().adminPaused());
        assertEquals(PauseReason.ADMIN, engine.view().pauseReason());
        assertTrue(engine.view().timingUncertain());
        long lateLived = engine.view().participants().get(late).lifeHumanNanos();
        now[0] = 100 * SECOND;
        var frozen = engine.view();
        assertEquals(challenge, frozen.challengeNanos());
        assertEquals(35 * SECOND, frozen.participants().get(monster).monsterCooldownNanos());
        assertEquals(35 * SECOND, frozen.participants().get(monster).huntLockNanos(human));
        assertEquals(lateLived, frozen.participants().get(late).lifeHumanNanos());
        accept(engine, engine.proposeAdminDeployCooldown(UUID.randomUUID(), round, monster, 1, 0));
        accept(engine, engine.proposeAdminHuntUnlock(UUID.randomUUID(), round, monster, null));
        var refused = assertThrows(Refusal.Exception.class, () -> engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 2, MonsterRole.ZOMBIE, human, 1), 2));
        assertEquals(Refusal.PAUSED, refused.refusal());
        assertThrows(IllegalStateException.class, () -> engine.proposeAdminPause(UUID.randomUUID(), round, true));
        // A clean restart keeps the pause.
        accept(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        var restarted = RoundEngine.restore(RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed())), () -> now[0]);
        assertTrue(restarted.view().adminPaused());
        for (var p : List.of(human, late, monster)) {
            accept(restarted, restarted.proposeOnline(UUID.randomUUID(), p, restarted.view().participants().get(p).name(), true));
        }
        assertTrue(restarted.view().adminPaused());
        accept(restarted, restarted.proposeAdminPause(UUID.randomUUID(), round, false));
        assertFalse(restarted.view().adminPaused());
        now[0] = 105 * SECOND;
        assertEquals(challenge + 5 * SECOND, restarted.view().challengeNanos());
        accept(restarted, restarted.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 2, MonsterRole.ZOMBIE, human, 1), 2));
    }

    @Test void settlingLiftsThePause() {
        var engine = live(0);
        accept(engine, engine.proposeAdminPause(UUID.randomUUID(), round, true));
        accept(engine, engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.FAILED, null, "test")));
        assertFalse(engine.view().adminPaused());
        assertEquals(PauseReason.SETTLED, engine.view().pauseReason());
    }

    private RoundEngine live(int coins) { return live(coins, RULES); }

    private RoundEngine live(int coins, RulesConfig rules) { return online(players(camp(coins)), rules); }

    private LinkedHashMap<UUID, Participant> players(Participant camp) {
        var players = new LinkedHashMap<UUID, Participant>();
        players.put(human, new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 1, false));
        players.put(late, new Participant(late, "Late", ParticipantRole.HUMAN_ACTIVE, true, false, 0, 50, 1, false));
        players.put(monster, camp);
        return players;
    }

    private RoundEngine online(LinkedHashMap<UUID, Participant> players, RulesConfig rules) {
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, rules, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), () -> now[0]);
        for (var p : players.values()) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        return engine;
    }

    private Participant camp(int coins) {
        var wallet = coins == 0 ? MonsterWallet.EMPTY : MonsterWallet.EMPTY.credit(coins);
        return new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2, Map.of(), wallet);
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
