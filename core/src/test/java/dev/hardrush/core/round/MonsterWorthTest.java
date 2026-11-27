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

    @Test void worthStartsAtLevelTenAndGainsALevelPerWeightedDamagePointUpToThirty() {
        assertEquals(10_000, MonsterWorth.milliLevels(RULES, MonsterWallet.EMPTY));
        assertEquals(10_550, MonsterWorth.milliLevels(RULES, MonsterWallet.EMPTY.creditDamage(human, 550, 1000, 20_000)));
        // The End bounty doubles counted damage before it becomes worth.
        assertEquals(22_000, MonsterWorth.milliLevels(RULES, MonsterWallet.EMPTY.creditDamage(human, 6_000, 2000, 20_000)));
        // One human's full per-sortie cap reaches the top level without any bounty.
        assertEquals(30_000, MonsterWorth.milliLevels(RULES, MonsterWallet.EMPTY.creditDamage(human, 40_000, 1000, 20_000)));
        var twoHumans = MonsterWallet.EMPTY.creditDamage(human, 20_000, 1000, 20_000).creditDamage(other, 20_000, 1000, 20_000);
        assertEquals(30_000, MonsterWorth.milliLevels(RULES, twoHumans));
    }

    @Test void theBarShowsWholeLevelsWithTheFractionAsFill() {
        assertEquals(10, MonsterWorth.level(10_000));
        assertEquals(0, MonsterWorth.progress(10_000));
        assertEquals(10, MonsterWorth.level(10_550));
        assertEquals(0.55f, MonsterWorth.progress(10_550), 1e-6);
        assertEquals(30, MonsterWorth.level(30_000));
        assertEquals(0, MonsterWorth.progress(30_000));
    }

    @Test void investmentAddsALevelPerFiveCoinsBesideBaseAndDamageUnderTheCap() {
        assertEquals(15_000, MonsterWorth.milliLevels(RULES, invested(25_000)));
        // Below a whole level the investment fills the bar: 3 coins are three fifths of a level.
        assertEquals(10_600, MonsterWorth.milliLevels(RULES, invested(3_000)));
        assertEquals(10_001, MonsterWorth.milliLevels(RULES, invested(5)));
        var both = invested(25_000).creditDamage(human, 6_000, 1000, 20_000);
        assertEquals(21_000, MonsterWorth.milliLevels(RULES, both));
        // Base, investment and damage together still stop at level 30.
        assertEquals(30_000, MonsterWorth.milliLevels(RULES, invested(80_000).creditDamage(human, 10_000, 1000, 20_000)));
        assertEquals(30_000, MonsterWorth.milliLevels(RULES, invested(Long.MAX_VALUE / 2)));
    }

    @Test void roundsSavedBeforeSpoilsCountNoInvestment() {
        assertTrue(MonsterWorth.spoilsEnabled(RULES));
        assertFalse(MonsterWorth.spoilsEnabled(RULES.withMode(GameMode.NORMAL)));
        var legacy = withoutSpoils();
        assertTrue(MonsterWorth.enabled(legacy));
        assertFalse(MonsterWorth.spoilsEnabled(legacy));
        assertEquals(10_000, MonsterWorth.milliLevels(legacy, invested(25_000)));
        var spoils = MonsterSpoils.roll(legacy, 30_000, bound -> 0);
        assertEquals(0, spoils.ironIngots());
        assertTrue(spoils.brewing().isEmpty());
        assertEquals(300, spoils.experience());
        assertEquals(30, spoils.bookLevel());
    }

    @Test void spoilsRulesComeAllOrNoneAndInRange() {
        for (var key : List.of(MonsterWorth.COINS_PER_LEVEL, MonsterWorth.PERMANENT_PERMILLE, MonsterWorth.LEVELS_PER_IRON)) {
            var partial = new TreeMap<>(RULES.combatValues());
            partial.remove(key);
            assertThrows(IllegalArgumentException.class, () -> rules(partial), key);
        }
        for (var bad : List.of(Map.entry(MonsterWorth.COINS_PER_LEVEL, 0.0), Map.entry(MonsterWorth.LEVELS_PER_IRON, 0.0),
                Map.entry(MonsterWorth.PERMANENT_PERMILLE, 1001.0), Map.entry(MonsterWorth.COINS_PER_LEVEL, 2.5))) {
            var values = new TreeMap<>(RULES.combatValues());
            values.put(bad.getKey(), bad.getValue());
            assertThrows(IllegalArgumentException.class, () -> rules(values), bad.toString());
        }
        var free = new TreeMap<>(RULES.combatValues());
        free.put(MonsterWorth.PERMANENT_PERMILLE, 0.0);
        assertEquals(0.0, rules(free).combatValues().get(MonsterWorth.PERMANENT_PERMILLE));
        // Spoils cannot stand without worth itself.
        var orphan = new TreeMap<>(RULES.combatValues());
        orphan.remove(MonsterWorth.BOOK_MIN_LEVEL);
        assertThrows(IllegalArgumentException.class, () -> rules(orphan));
    }

    @Test void pendingRulesGainTheSpoilsDefaults() {
        var restored = withoutSpoils().withEconomyDefaults();
        assertEquals(5.0, restored.combatValues().get(MonsterWorth.COINS_PER_LEVEL));
        assertEquals(100.0, restored.combatValues().get(MonsterWorth.PERMANENT_PERMILLE));
        assertEquals(7.0, restored.combatValues().get(MonsterWorth.LEVELS_PER_IRON));
        assertTrue(MonsterWorth.spoilsEnabled(restored));
        assertSame(RULES, RULES.withEconomyDefaults());
    }

    static RulesConfig withoutSpoils() {
        var values = new TreeMap<>(RULES.combatValues());
        values.keySet().removeAll(List.of(MonsterWorth.COINS_PER_LEVEL, MonsterWorth.PERMANENT_PERMILLE, MonsterWorth.LEVELS_PER_IRON));
        return rules(values);
    }

    private static MonsterWallet invested(long milliCoins) {
        return new MonsterWallet(0, false, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, Map.of(), "", false,
                Map.of(), milliCoins);
    }

    @Test void projectedCreditMatchesWhatTheEngineCommits() {
        var engine = engine();
        var entries = List.of(new RoundEngine.DamageCoins(monster, 1, 3, human, 1500, 4_000),
                new RoundEngine.DamageCoins(monster, 1, 2, human, 1000, 9_000));
        var projected = RoundEngine.creditDamageCoins(engine.view(), round, entries).get(monster);
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), round, entries));
        var committed = engine.committed().participants().get(monster);
        assertEquals(committed.wallet(), projected.wallet());
        assertEquals(16_000, MonsterWorth.milliLevels(RULES, committed.wallet()));
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
        fractional.put(MonsterWorth.LEVELS_PER_DAMAGE, 2.5);
        assertThrows(IllegalArgumentException.class, () -> rules(fractional));
        var inverted = new TreeMap<>(RULES.combatValues());
        inverted.put(MonsterWorth.BASE_LEVEL, 40.0);
        assertThrows(IllegalArgumentException.class, () -> rules(inverted));
    }

    static RulesConfig rules(Map<String, Double> values) {
        return new RulesConfig(RULES.schemaVersion(), GameMode.HARDCORE, RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(),
                RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
    }

    /** A running round with one human and a monster deployed as sortie 3. */
    private RoundEngine engine() {
        var players = new LinkedHashMap<UUID, Participant>();
        players.put(human, new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 50, 1, false));
        players.put(monster, new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2, Map.of()));
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), clock);
        for (var p : players.values()) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        accept(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 2));
        return engine;
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
