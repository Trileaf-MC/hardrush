package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.hunt.PersonalHuntRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** docs/plan/击败锁定与怪物平衡.md §1: a defeat locks only the humans who won it. */
class HuntLockTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    private static final long LOCK = 45_000_000_000L;
    private final UUID round = UUID.randomUUID();
    private final UUID quarry = UUID.randomUUID();
    private final UUID killer = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();
    private final long[] now = {0};

    @Test void aHumanKillLocksOnlyTheKillerAndBenchesNoOne() {
        var engine = deployed();
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.empty())), null));
        var p = engine.committed().participants().get(monster);
        assertEquals(ParticipantRole.MONSTER_LOBBY, p.role());
        assertEquals(0, p.monsterCooldownNanos(), "A defeat no longer benches the monster");
        assertEquals(Map.of(killer, LOCK), p.huntLocks());
        assertEquals(8, p.wallet().coins(), "Killed by a human pays the retuned eight coins");
    }

    @Test void aLockedHumanCannotBeHuntedButOthersCanAtOnce() {
        var engine = deployed();
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.empty())), null));
        var refused = assertThrows(Refusal.Exception.class, () -> engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 3, MonsterRole.ZOMBIE, killer, 1), 3));
        assertEquals(Refusal.HUNT_LOCKED, refused.refusal());
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 3, MonsterRole.ZOMBIE, quarry, 1), 3));
        assertEquals(ParticipantRole.MONSTER_DEPLOYED, engine.committed().participants().get(monster).role());
    }

    @Test void theLockRunsWhileHuntingOthersAndIsForgottenWhenSpent() {
        var engine = deployed();
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.empty())), null));
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 3, MonsterRole.ZOMBIE, quarry, 1), 3));
        now[0] = 30_000_000_000L;
        assertEquals(15_000_000_000L, engine.view().participants().get(monster).huntLockNanos(killer));
        now[0] = 46_000_000_000L;
        assertTrue(engine.view().participants().get(monster).huntLocks().isEmpty());
    }

    @Test void aCreepersOwnBlastLocksOnlyItsQuarryEvenWhenAHumanHitItFirst() {
        var engine = deployed();
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.of(new RoundDeath.Detonation(Optional.of(quarry), false)))), null));
        assertEquals(Map.of(quarry, LOCK), engine.committed().participants().get(monster).huntLocks());
    }

    @Test void aForcedBlastLocksTheQuarryAndTheIgniter() {
        var engine = deployed();
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.of(new RoundDeath.Detonation(Optional.of(quarry), true)))), null));
        assertEquals(Map.of(quarry, LOCK, killer, LOCK), engine.committed().participants().get(monster).huntLocks());
    }

    @Test void aDeathNobodyIsCreditedWithLocksNoOne() {
        var engine = deployed();
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.empty(), Optional.empty())), null));
        var p = engine.committed().participants().get(monster);
        assertTrue(p.huntLocks().isEmpty());
        assertEquals(0, p.monsterCooldownNanos());
    }

    @Test void aSecondDefeatRestartsTheLockWithoutStacking() {
        var engine = deployed();
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.empty())), null));
        now[0] = 20_000_000_000L;
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 3, MonsterRole.ZOMBIE, quarry, 1), 3));
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(monster, 1, 4, Optional.empty(), Optional.of(sword(killer)), true)), null));
        assertEquals(LOCK, engine.committed().participants().get(monster).huntLockNanos(killer));
    }

    @Test void disconnectingStillBenchesTheMonster() {
        var engine = deployed();
        accept(engine, engine.proposeOnline(UUID.randomUUID(), monster, "Monster", false));
        assertEquals(LOCK, engine.committed().participants().get(monster).monsterCooldownNanos());
    }

    @Test void revivingForgetsTheLocks() {
        var p = camp().withHuntLock(killer, LOCK).addRevivalCharges(1);
        assertTrue(p.revive(false).huntLocks().isEmpty());
    }

    @Test void pendingRulesStillOnAnEarlierDefaultFollowTheRetunedOne() {
        var values = new java.util.TreeMap<>(RULES.combatValues());
        values.put("monster.deployCooldownMillis", 30_000.0);
        values.put("monster.returnChannelMillis", 5_000.0);
        values.put("coin.killedByHuman", 5.0);
        values.remove("monster.retrackChannelMillis");
        var stored = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        assertEquals(30_000L, RoundEngine.deployCooldownNanos(stored) / 1_000_000, "A started round keeps its rules");
        assertEquals(5_000L, RoundEngine.retrackChannelMillis(stored), "Without its own setting the compass shares the return's");
        var pending = stored.forNewRound();
        assertEquals(45_000.0, pending.combatValues().get("monster.deployCooldownMillis"));
        assertEquals(4_000.0, pending.combatValues().get("monster.returnChannelMillis"));
        assertEquals(5_000.0, pending.combatValues().get("monster.retrackChannelMillis"));
        assertEquals(8.0, pending.combatValues().get("coin.killedByHuman"));
        values.put("monster.deployCooldownMillis", 60_000.0);
        var tuned = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        assertEquals(60_000.0, tuned.forNewRound().combatValues().get("monster.deployCooldownMillis"), "An operator's own value is kept");
    }

    private RoundDeath death(Optional<CombatOrigin> credit, Optional<RoundDeath.Detonation> detonation) {
        return new RoundDeath(monster, 1, 3, Optional.empty(), credit, credit.isPresent(), Optional.empty(), detonation);
    }

    private CombatOrigin sword(UUID human) {
        return new CombatOrigin(round, UUID.randomUUID(), new CombatActor(human, ParticipantRole.HUMAN_ACTIVE, 1, 0),
                AttackKind.MELEE, Optional.empty(), List.of());
    }

    private static Participant human(UUID id) {
        return new Participant(id, id.toString().substring(0, 8), ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 1, false);
    }

    private Participant camp() {
        return new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2, Map.of(), MonsterWallet.EMPTY);
    }

    /** A monster out on its third sortie, hunting {@link #quarry}. */
    private RoundEngine deployed() {
        var players = new LinkedHashMap<UUID, Participant>();
        for (var p : List.of(human(quarry), human(killer), camp())) players.put(p.id(), p);
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), () -> now[0]);
        for (var p : players.values()) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 2, MonsterRole.ZOMBIE, quarry, 1), 2));
        return engine;
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
