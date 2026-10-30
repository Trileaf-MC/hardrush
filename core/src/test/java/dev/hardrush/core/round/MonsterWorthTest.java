package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MonsterWorthTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    private final UUID round = UUID.randomUUID();
    private final UUID human = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();
    private final MonotonicClock clock = () -> 0;

    @Test void worthIsTheBasePlusTenPerWeightedDamagePointCappedAt315() {
        assertEquals(100, MonsterWorth.experience(RULES, MonsterWallet.EMPTY));
        // 0.55 damage points round down to 5 experience.
        assertEquals(105, MonsterWorth.experience(RULES, MonsterWallet.EMPTY.creditDamage(human, 550, 1000, 20_000)));
        // The End bounty doubles counted damage before it becomes worth.
        assertEquals(220, MonsterWorth.experience(RULES, MonsterWallet.EMPTY.creditDamage(human, 6_000, 2000, 20_000)));
        assertEquals(300, MonsterWorth.experience(RULES, MonsterWallet.EMPTY.creditDamage(human, 40_000, 1000, 20_000)));
        var twoHumans = MonsterWallet.EMPTY.creditDamage(human, 20_000, 1000, 20_000).creditDamage(other, 20_000, 1000, 20_000);
        assertEquals(315, MonsterWorth.experience(RULES, twoHumans));
    }

    @Test void levelsAndProgressFollowTheVanillaExperienceCurve() {
        assertEquals(0, MonsterWorth.level(0));
        assertEquals(0, MonsterWorth.progress(0));
        assertEquals(1, MonsterWorth.level(7));
        assertEquals(7, MonsterWorth.level(100));
        assertEquals(9f / 21, MonsterWorth.progress(100), 1e-6);
        assertEquals(9, MonsterWorth.level(159));
        assertEquals(10, MonsterWorth.level(160));
        assertEquals(0, MonsterWorth.progress(160));
        assertEquals(15, MonsterWorth.level(315));
        assertEquals(16, MonsterWorth.level(352));
        assertEquals(30, MonsterWorth.level(1395));
    }

    @Test void onlyWorthOfLevelTenLeavesABookEnchantedAtTwiceTheLevel() {
        assertEquals(0, MonsterWorth.bookCost(RULES, 100));
        assertEquals(0, MonsterWorth.bookCost(RULES, 159));
        assertEquals(20, MonsterWorth.bookCost(RULES, 160));
        assertEquals(30, MonsterWorth.bookCost(RULES, 315));
    }

    @Test void projectedCreditMatchesWhatTheEngineCommits() {
        var engine = engine();
        var entries = List.of(new RoundEngine.DamageCoins(monster, 1, 3, human, MonsterDimension.NETHER, 4_000),
                new RoundEngine.DamageCoins(monster, 1, 2, human, MonsterDimension.OVERWORLD, 9_000));
        var projected = RoundEngine.creditDamageCoins(engine.view(), round, entries).get(monster);
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), round, entries));
        var committed = engine.committed().participants().get(monster);
        assertEquals(committed.wallet(), projected.wallet());
        assertEquals(160, MonsterWorth.experience(RULES, committed.wallet()));
    }

    @Test void roundsWithoutWorthRulesOrOutsideHardcoreKeepWorthDisabled() {
        assertTrue(MonsterWorth.enabled(RULES));
        assertFalse(MonsterWorth.enabled(RULES.withMode(GameMode.NORMAL)));
        var values = new TreeMap<>(RULES.combatValues());
        values.keySet().removeIf(key -> key.startsWith("worth."));
        var legacy = rules(values);
        assertFalse(MonsterWorth.enabled(legacy));
        assertTrue(MonsterWorth.enabled(legacy.forNewRound()));
    }

    @Test void worthRulesMustBeCompleteWholeAndOrdered() {
        var partial = new TreeMap<>(RULES.combatValues());
        partial.remove(MonsterWorth.BOOK_MIN_LEVEL);
        assertThrows(IllegalArgumentException.class, () -> rules(partial));
        var fractional = new TreeMap<>(RULES.combatValues());
        fractional.put(MonsterWorth.XP_PER_DAMAGE, 2.5);
        assertThrows(IllegalArgumentException.class, () -> rules(fractional));
        var inverted = new TreeMap<>(RULES.combatValues());
        inverted.put(MonsterWorth.BASE_XP, 400.0);
        assertThrows(IllegalArgumentException.class, () -> rules(inverted));
    }

    private static RulesConfig rules(Map<String, Double> values) {
        return new RulesConfig(RULES.schemaVersion(), GameMode.HARDCORE, RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.protectionRadius(), RULES.startCountdownMillis(),
                RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
    }

    /** A running round with one human and a monster deployed as sortie 3. */
    private RoundEngine engine() {
        var players = new LinkedHashMap<UUID, Participant>();
        players.put(human, new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 50, 1, false, true));
        players.put(monster, new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0, true,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2, Map.of()));
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), clock);
        for (var p : players.values()) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        accept(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 2));
        return engine;
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
