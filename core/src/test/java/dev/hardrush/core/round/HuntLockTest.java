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

/**
 * docs/plan/悬赏分档与追猎强度.md §3, §4 and §6: a monster that falls on a sortie is locked from its quarry and from
 * the human who killed it, each for as long as that human's stars say; a return locks nobody; a human's stars
 * cap how many monsters hunt it at once and price its head.
 */
class HuntLockTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    /** One star: the longest lock. */
    private static final long LOCK = 120_000_000_000L;
    private final UUID round = UUID.randomUUID();
    private final UUID quarry = UUID.randomUUID();
    private final UUID killer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final long[] now = {0};

    /** 19 points: three stars. */
    private static final HumanThreat NETHER = new HumanThreat(1, 0, java.util.Set.of("minecraft:story/mine_diamond",
            "minecraft:story/enter_the_nether", "minecraft:story/smelt_iron", "minecraft:story/obtain_armor",
            "minecraft:story/iron_tools", "minecraft:story/lava_bucket", "minecraft:story/form_obsidian"));

    @Test void aHumanKillLocksTheQuarryAndTheKillerAndBenchesNoOne() {
        var engine = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.empty())), null));
        var p = engine.committed().participants().get(monster);
        assertEquals(ParticipantRole.MONSTER_LOBBY, p.role());
        assertEquals(Optional.empty(), p.quarry());
        assertEquals(0, p.monsterCooldownNanos(), "A defeat no longer benches the monster");
        assertEquals(Map.of(quarry, LOCK, killer, LOCK), p.huntLocks());
        assertEquals(8, p.wallet().coins(), "A one-star killer pays eight coins");
    }

    @Test void eachLockAndTheCoinsFollowThatHumansOwnStars() {
        var engine = deployed(HumanThreat.NONE, NETHER);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.empty())), null));
        var p = engine.committed().participants().get(monster);
        assertEquals(Map.of(quarry, LOCK, killer, 60_000_000_000L), p.huntLocks(), "Three stars lock for a minute");
        assertEquals(13, p.wallet().coins(), "A three-star killer pays thirteen");
    }

    @Test void anEnvironmentalDeathStillLocksTheQuarry() {
        var engine = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.empty(), Optional.empty())), null));
        var p = engine.committed().participants().get(monster);
        assertEquals(Map.of(quarry, LOCK), p.huntLocks());
        assertEquals(0, p.monsterCooldownNanos());
        assertEquals(0, p.wallet().coins(), "Nobody hit it: no coins");
    }

    @Test void aDeliberateReturnLocksNobody() {
        var engine = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3));
        var p = engine.committed().participants().get(monster);
        assertTrue(p.huntLocks().isEmpty());
        assertEquals(Optional.empty(), p.quarry());
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 3, MonsterRole.ZOMBIE, quarry, 1), 3));
    }

    @Test void aLockedHumanCannotBeHuntedButOthersCanAtOnce() {
        var engine = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.empty())), null));
        for (UUID locked : List.of(quarry, killer)) {
            var refused = assertThrows(Refusal.Exception.class, () -> engine.proposePersonalHunt(UUID.randomUUID(),
                    new PersonalHuntRequest(round, monster, 1, 3, MonsterRole.ZOMBIE, locked, 1), 3));
            assertEquals(Refusal.HUNT_LOCKED, refused.refusal());
        }
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 3, MonsterRole.ZOMBIE, other, 1), 3));
        assertEquals(ParticipantRole.MONSTER_DEPLOYED, engine.committed().participants().get(monster).role());
    }

    @Test void theLockRunsWhileHuntingOthersAndIsForgottenWhenSpent() {
        var engine = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.empty())), null));
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 3, MonsterRole.ZOMBIE, other, 1), 3));
        now[0] = 30_000_000_000L;
        assertEquals(90_000_000_000L, engine.view().participants().get(monster).huntLockNanos(killer));
        now[0] = 121_000_000_000L;
        assertTrue(engine.view().participants().get(monster).huntLocks().isEmpty());
    }

    @Test void aCreepersOwnBlastLocksItsQuarryButNotWhoeverHitItFirst() {
        var engine = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.of(new RoundDeath.Detonation(Optional.of(quarry), Optional.empty())))), null));
        assertEquals(Map.of(quarry, LOCK), engine.committed().participants().get(monster).huntLocks());
    }

    @Test void aForcedBlastLocksTheQuarryAndTheIgniter() {
        var engine = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.empty(), Optional.of(new RoundDeath.Detonation(Optional.of(quarry), Optional.of(killer))))), null));
        assertEquals(Map.of(quarry, LOCK, killer, LOCK), engine.committed().participants().get(monster).huntLocks(),
                "The igniter is locked even when no kill credit names it");
    }

    @Test void aSecondDefeatRestartsTheLockWithoutStacking() {
        var engine = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(death(Optional.of(sword(killer)), Optional.empty())), null));
        now[0] = 20_000_000_000L;
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 3, MonsterRole.ZOMBIE, other, 1), 3));
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(monster, 1, 4, Optional.empty(), Optional.of(sword(killer)), true)), null));
        assertEquals(LOCK, engine.committed().participants().get(monster).huntLockNanos(killer));
        assertEquals(LOCK, engine.committed().participants().get(monster).huntLockNanos(other));
        assertEquals(100_000_000_000L, engine.committed().participants().get(monster).huntLockNanos(quarry));
    }

    @Test void aHumansStarsCapTheMonstersOutOnIt() {
        var engine = deployed(NETHER, HumanThreat.NONE);
        accept(engine, engine.proposeOnline(UUID.randomUUID(), second, "Second", true));
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, second, 1, 0, MonsterRole.ZOMBIE, quarry, 1), 0));
        assertEquals(2, RoundEngine.huntersOn(engine.committed(), quarry), "Three stars take two at once here");
        var engineOne = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engineOne, engineOne.proposeOnline(UUID.randomUUID(), second, "Second", true));
        var full = assertThrows(Refusal.Exception.class, () -> engineOne.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, second, 1, 0, MonsterRole.ZOMBIE, quarry, 1), 0));
        assertEquals(Refusal.HUNT_FULL, full.refusal());
    }

    @Test void aHumansHeadPaysItsStarsToTheMonsterThatTookIt() {
        for (var entry : Map.of(HumanThreat.NONE, 0, NETHER, 80).entrySet()) {
            var engine = deployed(entry.getKey(), HumanThreat.NONE);
            var claw = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(monster, ParticipantRole.MONSTER_DEPLOYED, 1, 3),
                    AttackKind.MELEE, Optional.empty(), List.of());
            accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                    List.of(new RoundDeath(quarry, 1, 0, Optional.empty(), Optional.of(claw), false)), null));
            var p = engine.committed().participants().get(monster);
            assertEquals(entry.getValue(), p.wallet().coins());
            assertEquals(1, p.revivalCharges(), "The kill still pays its totem");
        }
    }

    @Test void disconnectingStillBenchesTheMonster() {
        var engine = deployed(HumanThreat.NONE, HumanThreat.NONE);
        accept(engine, engine.proposeOnline(UUID.randomUUID(), monster, "Monster", false));
        assertEquals(45_000_000_000L, engine.committed().participants().get(monster).monsterCooldownNanos());
    }

    @Test void revivingForgetsTheLocks() {
        var p = camp().withHuntLock(killer, LOCK).addRevivalCharges(1);
        assertTrue(p.revive(false).huntLocks().isEmpty());
    }

    @Test void pendingRulesStillOnAnEarlierDefaultFollowTheRetunedOne() {
        var values = new java.util.TreeMap<>(RULES.combatValues());
        values.put("monster.deployCooldownMillis", 30_000.0);
        values.put("monster.returnChannelMillis", 5_000.0);
        values.remove("monster.retrackChannelMillis");
        values.remove(RulesConfig.DEFAULTS_REVISION_KEY); // stored before defaults carried a revision
        var stored = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        assertEquals(30_000L, RoundEngine.deployCooldownNanos(stored) / 1_000_000, "A started round keeps its rules");
        assertEquals(5_000L, RoundEngine.retrackChannelMillis(stored), "Without its own setting the compass shares the return's");
        var pending = stored.forNewRound();
        assertEquals(45_000.0, pending.combatValues().get("monster.deployCooldownMillis"));
        assertEquals(4_000.0, pending.combatValues().get("monster.returnChannelMillis"));
        assertEquals(5_000.0, pending.combatValues().get("monster.retrackChannelMillis"));
        assertNull(pending.combatValues().get("coin.killedByHuman"), "Stars decide what a slain monster earns now");
        assertEquals(20.0, pending.combatValues().get("coin.killedByHuman.star5"));
        // Once migrated, the revision is stored: an operator may choose the earlier default again and keep it.
        var chosen = new java.util.TreeMap<>(pending.combatValues());
        chosen.put("monster.deployCooldownMillis", 30_000.0);
        chosen.put("coin.killedByHuman.star1", 5.0);
        var operator = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), chosen, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        var reopened = RulesConfig.decode(operator.forNewRound().encode()).forNewRound().forNewRound();
        assertEquals(30_000.0, reopened.combatValues().get("monster.deployCooldownMillis"), "A chosen earlier default survives restarts");
        assertEquals(5.0, reopened.combatValues().get("coin.killedByHuman.star1"));
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

    private static Participant human(UUID id, HumanThreat threat) {
        return new Participant(id, id.toString().substring(0, 8), ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, false, threat);
    }

    private Participant camp() {
        return new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2, Map.of(), MonsterWallet.EMPTY);
    }

    /** A monster out on its third sortie, hunting {@link #quarry}; a second monster waits in the camp. */
    private RoundEngine deployed(HumanThreat quarryThreat, HumanThreat killerThreat) {
        var players = new LinkedHashMap<UUID, Participant>();
        var waiting = new Participant(second, "Second", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY);
        for (var p : List.of(human(quarry, quarryThreat), human(killer, killerThreat), human(other, HumanThreat.NONE), camp(), waiting))
            players.put(p.id(), p);
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), () -> now[0]);
        for (var p : players.values()) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, 2, MonsterRole.ZOMBIE, quarry, 1), 2));
        return engine;
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
