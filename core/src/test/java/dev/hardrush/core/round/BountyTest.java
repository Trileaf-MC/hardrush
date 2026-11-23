package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.hunt.PersonalHuntRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BountyTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    private final UUID round = UUID.randomUUID();
    private final UUID strong = UUID.randomUUID();
    private final UUID weak = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();

    private static HumanThreat milestones(int life, String... ids) {
        return new HumanThreat(life, 0, 0, Set.of(ids));
    }

    private static Participant human(UUID id, boolean online, HumanThreat threat) {
        return new Participant(id, id.toString().substring(0, 8), ParticipantRole.HUMAN_ACTIVE, online, false, 0, 0, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, false, threat);
    }

    private static Map<UUID, Participant> players(Participant... participants) {
        var players = new LinkedHashMap<UUID, Participant>();
        for (var p : participants) players.put(p.id(), p);
        return players;
    }

    @Test void milestonesAreWeightedAndOnlyScoredOncePerLife() {
        var threat = HumanThreat.NONE.reached(1, "minecraft:story/mine_stone").reached(1, "minecraft:story/mine_diamond")
                .reached(1, "minecraft:story/mine_diamond").reached(1, "minecraft:husbandry/root");
        assertEquals(Set.of("minecraft:story/mine_stone", "minecraft:story/mine_diamond"), threat.milestones());
        assertEquals(5, Bounty.threat(RULES, human(strong, true, threat)));
        var fought = threat.killedMonster(1).huntedFromCamp(1).huntedFromCamp(1);
        assertEquals(5 + 3 + 2, Bounty.threat(RULES, human(strong, true, fought)), "Kill 3, each hunt 1");
    }

    @Test void anotherLifesRecordReadsAsEmpty() {
        var old = milestones(1, "minecraft:story/enter_the_end");
        var revived = new Participant(strong, "S", ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 2, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, false, old);
        assertEquals(0, Bounty.threat(RULES, revived));
        assertEquals(Set.of("minecraft:story/mine_stone"), old.reached(2, "minecraft:story/mine_stone").milestones());
    }

    @Test void starsAreRelativeToTheStrongestOnlineHuman() {
        // 30 points: diamonds, nether, iron, shiny gear, blaze rod and more.
        var top = milestones(1, "minecraft:story/enter_the_nether", "minecraft:story/shiny_gear",
                "minecraft:nether/obtain_blaze_rod", "minecraft:story/mine_diamond", "minecraft:nether/find_fortress",
                "minecraft:story/enchant_item", "minecraft:story/smelt_iron");
        assertEquals(30, Bounty.threat(RULES, human(strong, true, top)));
        var third = UUID.randomUUID();
        var standings = Bounty.standings(RULES, players(human(strong, true, top),
                human(weak, true, milestones(1, "minecraft:story/mine_stone")),
                human(third, true, milestones(1, "minecraft:story/mine_diamond", "minecraft:story/enter_the_nether",
                        "minecraft:story/smelt_iron", "minecraft:story/obtain_armor", "minecraft:story/iron_tools"))))
        ;
        assertEquals(new Bounty.Standing(30, 5, true), standings.get(strong));
        assertEquals(1, standings.get(weak).stars());
        assertEquals(3, standings.get(third).stars(), "15 of 30 is past 45%");
        assertFalse(standings.get(third).leader());
    }

    @Test void theFloorKeepsAnOpeningLeadLowAndNamesNoLeader() {
        var standings = Bounty.standings(RULES, players(human(strong, true, milestones(1, "minecraft:story/mine_stone",
                "minecraft:story/upgrade_tools")), human(weak, true, HumanThreat.NONE)));
        assertEquals(2, standings.get(strong).stars(), "2 of the floor 10 is 20%");
        assertFalse(standings.get(strong).leader());
        assertEquals(1, standings.get(weak).stars());
    }

    @Test void offlineHumansKeepTheirThreatButDoNotSetTheTop() {
        var end = milestones(1, "minecraft:story/enter_the_end", "minecraft:story/follow_ender_eye");
        var standings = Bounty.standings(RULES, players(human(strong, false, end),
                human(weak, true, milestones(1, "minecraft:story/enter_the_nether", "minecraft:story/mine_diamond",
                        "minecraft:story/smelt_iron"))));
        assertEquals(new Bounty.Standing(11, 5, true), standings.get(weak), "The online leader is not pushed down");
        assertEquals(18, standings.get(strong).threat());
        assertFalse(standings.get(strong).leader(), "An absent human cannot be the top bounty");
    }

    @Test void tiedLeadersShareTheTopBounty() {
        var threat = milestones(1, "minecraft:story/enter_the_end");
        var standings = Bounty.standings(RULES, players(human(strong, true, threat), human(weak, true, threat)));
        assertTrue(standings.get(strong).leader());
        assertTrue(standings.get(weak).leader());
    }

    @Test void starMultipliersAndText() {
        assertEquals(List.of(1000, 1250, 1500, 2000, 2500),
                List.of(Bounty.permille(RULES, 1), Bounty.permille(RULES, 2), Bounty.permille(RULES, 3),
                        Bounty.permille(RULES, 4), Bounty.permille(RULES, 5)));
        assertEquals("★★★☆☆", Bounty.starText(3));
        assertThrows(IllegalArgumentException.class, () -> Bounty.starText(0));
    }

    @Test void roundsWithoutBountyKeysKeepThePlainRate() {
        var values = new TreeMap<>(RULES.combatValues());
        values.keySet().removeIf(key -> key.startsWith("bounty."));
        var legacy = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        assertFalse(Bounty.enabled(legacy));
        assertEquals(1000, Bounty.permille(legacy, 5));
        assertTrue(Bounty.standings(legacy, players(human(strong, true, milestones(1, "minecraft:story/enter_the_end")))).isEmpty());
        assertFalse(Bounty.enabled(RULES.withMode(GameMode.NORMAL)), "Normal mode has no monster economy");
    }

    @Test void bountyConfigurationMustBeCompleteAndOrdered() {
        var values = new TreeMap<>(RULES.combatValues());
        values.put("bounty.star3Ratio", 0.1);
        assertThrows(IllegalArgumentException.class, () -> new RulesConfig(RULES.schemaVersion(), RULES.mode(),
                RULES.minecraftVersion(), RULES.difficulty(), RULES.generationRule(), values,
                RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis()));
        var partial = new TreeMap<>(RULES.combatValues());
        partial.remove("bounty.floor");
        assertThrows(IllegalArgumentException.class, () -> new RulesConfig(RULES.schemaVersion(), RULES.mode(),
                RULES.minecraftVersion(), RULES.difficulty(), RULES.generationRule(), partial,
                RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis()));
    }

    @Test void pendingRulesDropRetiredCoinBountyKeys() {
        var values = new TreeMap<>(RULES.combatValues());
        values.keySet().removeIf(key -> key.startsWith("bounty."));
        values.put("coin.bounty.netherBonus", 0.5);
        values.put("coin.survival.tier1Minutes", 10.0);
        var stored = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        var upgraded = stored.withEconomyDefaults();
        assertTrue(upgraded.combatValues().keySet().stream().noneMatch(key -> key.startsWith("coin.bounty.") || key.startsWith("coin.survival.")));
        assertTrue(Bounty.enabled(upgraded));
    }

    @Test void huntingFromTheCampMarksTheTarget() {
        var engine = engine(human(strong, true, HumanThreat.NONE), camp());
        var request = new PersonalHuntRequest(round, monster, 1, 2, MonsterRole.ZOMBIE, strong, 1);
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(), request, 2));
        var target = engine.committed().participants().get(strong);
        assertEquals(new HumanThreat(1, 0, 1, Set.of()), target.threat());
        assertEquals(ParticipantRole.MONSTER_DEPLOYED, engine.committed().participants().get(monster).role());
    }

    @Test void killingADeployedMonsterPlayerScoresTheHumanKiller() {
        var engine = engine(human(strong, true, HumanThreat.NONE), camp());
        accept(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 2));
        var sword = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(strong, ParticipantRole.HUMAN_ACTIVE, 1, 0),
                AttackKind.MELEE, Optional.empty(), List.of());
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(monster, 1, 3, Optional.of(lethal(monster, sword)), true)), null));
        assertEquals(1, engine.committed().participants().get(strong).threat().monsterKills());
        assertEquals(3, Bounty.threat(RULES, engine.committed().participants().get(strong)));
    }

    @Test void aKillerFromAnEarlierLifeScoresNothing() {
        var engine = engine(human(strong, true, HumanThreat.NONE), camp());
        accept(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 2));
        var stale = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(strong, ParticipantRole.HUMAN_ACTIVE, 2, 0),
                AttackKind.MELEE, Optional.empty(), List.of());
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(monster, 1, 3, Optional.of(lethal(monster, stale)), true)), null));
        assertEquals(0, engine.committed().participants().get(strong).threat().monsterKills());
    }

    @Test void milestonesFollowTheLifeTheyWereReachedIn() {
        var engine = engine(human(strong, true, HumanThreat.NONE));
        accept(engine, engine.proposeThreatMilestone(UUID.randomUUID(), round, strong, 1, "minecraft:story/mine_diamond"));
        accept(engine, engine.proposeThreatMilestone(UUID.randomUUID(), round, strong, 0, "minecraft:story/enter_the_end"));
        accept(engine, engine.proposeThreatMilestone(UUID.randomUUID(), round, strong, 1, "minecraft:adventure/root"));
        assertEquals(4, Bounty.threat(RULES, engine.committed().participants().get(strong)));
    }

    @Test void damageKeepsTheWeightItLandedWith() {
        var engine = engine(human(strong, true, HumanThreat.NONE), camp());
        accept(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 2));
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), round,
                List.of(new RoundEngine.DamageCoins(monster, 1, 3, strong, 2500, 4_000))));
        assertEquals(10, engine.committed().participants().get(monster).wallet().coins(), "Four hearts at ×2.5");
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), round,
                List.of(new RoundEngine.DamageCoins(monster, 1, 3, strong, 90_000, 1_000))));
        assertEquals(12, engine.committed().participants().get(monster).wallet().coins(), "A forged weight is capped at five stars");
    }

    @Test void snapshotsRoundTripTheThreat() {
        var threat = new HumanThreat(1, 2, 5, Set.of("minecraft:story/mine_diamond", "minecraft:story/enter_the_nether"));
        var snapshot = new RoundSnapshot(round, 1, 2, 3, RULES, RoundPhase.RUNNING, PauseReason.NONE, 0, 0,
                Map.of(strong, human(strong, true, threat)), false, false, null);
        assertEquals(snapshot, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(snapshot)));
    }

    private Participant camp() {
        return new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2, Map.of());
    }

    private RoundEngine engine(Participant... participants) {
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players(participants), true, false, null), () -> 0);
        for (var p : participants) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        return engine;
    }

    private CombatEvent lethal(UUID victim, CombatOrigin origin) {
        var source = Optional.of(origin);
        return new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 1, victim, source,
                CombatEvent.classify(round, source), "test:lethal", Optional.empty(), Optional.empty(), 20, true, 0);
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
