package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.hunt.PersonalHuntRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** docs/plan/悬赏分档与追猎强度.md §3–§5: what a human's stars press it with, and a sortie's tier. */
class HuntPressureTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    /** 19 points: three stars. */
    private static final HumanThreat NETHER = new HumanThreat(1, 0, Set.of("minecraft:story/mine_diamond",
            "minecraft:story/enter_the_nether", "minecraft:story/smelt_iron", "minecraft:story/obtain_armor",
            "minecraft:story/iron_tools", "minecraft:story/lava_bucket", "minecraft:story/form_obsidian"));
    private final UUID round = UUID.randomUUID();
    private final UUID strong = UUID.randomUUID();
    private final UUID weak = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();

    @Test void tablesFollowThePlan() {
        assertEquals(List.of(1, 2, 3, 4, 5), stars(HuntPressure::concurrentHunters));
        assertEquals(List.of(120_000L, 90_000L, 60_000L, 45_000L, 30_000L),
                List.of(1, 2, 3, 4, 5).stream().map(HuntPressure::lockMillis).toList());
        assertEquals(List.of(0, 1, 2, 3, 3), stars(HuntPressure::starterAdultEggs));
        assertEquals(List.of(40_000L, 30_000L, 20_000L, 10_000L, 10_000L),
                List.of(1, 2, 3, 4, 5).stream().map(HuntPressure::volleyCooldownMillis).toList());
        assertEquals(HuntPressure.armor(1), HuntPressure.armor(2), "One and two stars wear vanilla's own roll");
        assertTrue(HuntPressure.armor(2).materialWeights().isEmpty());
        assertEquals(1F, HuntPressure.armor(5).wearChance());
        assertEquals(0F, HuntPressure.armor(5).stopChance(), "Five stars always wear a whole set");
        assertEquals(1, HuntPressure.tier(0));
        assertEquals(5, HuntPressure.tier(9));
    }

    @Test void fiveStarsWearAtLeastAFullIronSetNineTimesInTen() {
        var weights = HuntPressure.armor(5).materialWeights().orElseThrow();
        int total = weights.stream().mapToInt(Integer::intValue).sum();
        assertEquals(0.9, (double) (weights.get(4) + weights.get(5)) / total, 1e-9, "Iron or diamond");
    }

    @Test void aResumedSortieNeverClimbs() {
        assertEquals(1, HuntPressure.resumedTier(5, 1));
        assertEquals(3, HuntPressure.resumedTier(3, 5));
    }

    @Test void aSortieTakesItsQuarrysStarsAndAPausedOneDropsToAWeakerQuarry() {
        var engine = engine();
        hunt(engine, strong, 2);
        assertEquals(Sortie.onto(strong, 3), engine.committed().participants().get(monster).sortie());
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3, "body"));
        var camp = engine.committed().participants().get(monster);
        assertEquals(new Sortie(Optional.empty(), 3), camp.sortie(), "The paused body keeps its tier, not its quarry");
        hunt(engine, weak, 3);
        assertEquals(Sortie.onto(weak, 1), engine.committed().participants().get(monster).sortie());
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 4, "body"));
        hunt(engine, strong, 4);
        assertEquals(Sortie.onto(strong, 1), engine.committed().participants().get(monster).sortie(), "Never back up");
    }

    @Test void anEndedSortieForgetsItsTier() {
        var engine = engine();
        hunt(engine, strong, 2);
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3));
        assertEquals(Sortie.NONE, engine.committed().participants().get(monster).sortie());
        hunt(engine, weak, 3);
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 4));
        hunt(engine, strong, 4);
        assertEquals(Sortie.onto(strong, 3), engine.committed().participants().get(monster).sortie());
    }

    @Test void aVolleyRestsByItsSortiesTier() {
        var engine = engine();
        hunt(engine, strong, 2);
        accept(engine, engine.proposeMonsterSkill(UUID.randomUUID(), round, monster, 1, 3, MonsterSkill.SKELETON_BURST, 1_000));
        assertEquals(1_000 + 20_000L, engine.committed().participants().get(monster).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3));
        hunt(engine, weak, 3);
        accept(engine, engine.proposeMonsterSkill(UUID.randomUUID(), round, monster, 1, 4, MonsterSkill.SKELETON_BURST, 100_000));
        assertEquals(100_000 + 40_000L, engine.committed().participants().get(monster).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
    }

    private static List<Integer> stars(java.util.function.IntUnaryOperator table) {
        return List.of(1, 2, 3, 4, 5).stream().map(table::applyAsInt).toList();
    }

    private void hunt(RoundEngine engine, UUID target, int sortie) {
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, sortie, MonsterRole.SKELETON, target, 1), sortie));
    }

    private static Participant human(UUID id, HumanThreat threat) {
        return new Participant(id, id.toString().substring(0, 8), ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, false, threat);
    }

    private RoundEngine engine() {
        var players = new LinkedHashMap<UUID, Participant>();
        var camp = new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.SKELETON, MonsterDimension.OVERWORLD, 0, 2, Map.of(), MonsterWallet.EMPTY);
        for (var p : List.of(human(strong, NETHER), human(weak, HumanThreat.NONE), camp)) players.put(p.id(), p);
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), () -> 0);
        for (var p : players.values()) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        return engine;
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
