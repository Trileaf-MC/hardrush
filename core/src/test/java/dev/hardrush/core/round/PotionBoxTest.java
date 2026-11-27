package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class PotionBoxTest {
    private static final RulesConfig RULES = RulesConfig.defaults();

    /** Replays scripted {@code nextInt(bound)} answers in order. */
    private static RandomGenerator script(int... answers) {
        var queue = new ArrayDeque<Integer>();
        for (int answer : answers) queue.add(answer);
        return new RandomGenerator() {
            @Override public long nextLong() { throw new AssertionError("unscripted draw"); }
            @Override public int nextInt(int bound) {
                int answer = queue.remove();
                assertTrue(answer >= 0 && answer < bound, "scripted " + answer + " outside " + bound);
                return answer;
            }
        };
    }

    @Test void boxesDrawOnePerPoolBestFirst() {
        var random = new Random(7);
        for (int i = 0; i < 2_000; i++) {
            for (var role : MonsterRole.values()) {
                var rare = PotionBox.RARE.draw(RULES, role, random);
                assertEquals(2, rare.size());
                assertEquals(PotionBrew.Grade.RARE, rare.get(0).grade());
                assertTrue(java.util.Set.of(PotionBrew.Grade.COMMON, PotionBrew.Grade.NEGATIVE).contains(rare.get(1).grade()));
                var epic = PotionBox.EPIC.draw(RULES, role, random);
                assertEquals(List.of(PotionBrew.Grade.EPIC, PotionBrew.Grade.RARE), epic.subList(0, 2).stream().map(PotionBox.Dose::grade).toList());
                for (var dose : epic) {
                    assertTrue(dose.brew().drawableBy(role), dose + " for " + role);
                    assertEquals(dose.brew().grade(), dose.grade());
                    assertTrue(dose.seconds() <= PotionBox.MAX_SECONDS);
                    assertEquals(dose.brew().untilDeath(), dose.seconds() == PotionBox.UNTIL_DEATH);
                    assertEquals(dose.brew().instant(), dose.seconds() == 0);
                    if (dose.brew() == PotionBrew.INVISIBILITY) assertTrue(dose.seconds() <= PotionBrew.INVISIBILITY_MAX_SECONDS);
                    if (dose.brew() == PotionBrew.TURTLE_MASTER) assertEquals(PotionBrew.TURTLE_MASTER_SECONDS, dose.seconds());
                    if (!dose.brew().leveled()) assertEquals(0, dose.level());
                }
            }
        }
    }

    @Test void formsOnlyDrawWhatWorksOnThem() {
        assertFalse(PotionBrew.pool(PotionBrew.Grade.RARE, MonsterRole.CREEPER).contains(PotionBrew.STRENGTH));
        assertTrue(PotionBrew.pool(PotionBrew.Grade.RARE, MonsterRole.SKELETON).contains(PotionBrew.STRENGTH));
        assertFalse(PotionBrew.pool(PotionBrew.Grade.RARE, MonsterRole.ZOMBIE).contains(PotionBrew.REGENERATION));
        assertTrue(PotionBrew.pool(PotionBrew.Grade.RARE, MonsterRole.CREEPER).contains(PotionBrew.REGENERATION));
        assertTrue(PotionBrew.pool(PotionBrew.Grade.COMMON, MonsterRole.CREEPER).contains(PotionBrew.WATER_BREATHING));
        assertFalse(PotionBrew.pool(PotionBrew.Grade.COMMON, MonsterRole.ZOMBIE).contains(PotionBrew.WATER_BREATHING), "The undead never drown");
        assertTrue(PotionBrew.pool(PotionBrew.Grade.COMMON, MonsterRole.SKELETON).contains(PotionBrew.DOLPHINS_GRACE));
        assertFalse(PotionBrew.pool(PotionBrew.Grade.NEGATIVE, MonsterRole.CREEPER).contains(PotionBrew.BLINDNESS));
        assertTrue(PotionBrew.pool(PotionBrew.Grade.NEGATIVE, MonsterRole.ZOMBIE).contains(PotionBrew.BLINDNESS));
        assertEquals("伤害药水", PotionBrew.MENDING.potionName(MonsterRole.ZOMBIE));
        assertEquals("治疗药水", PotionBrew.MENDING.potionName(MonsterRole.CREEPER));
        assertEquals("治疗药水", PotionBrew.WOUNDING.potionName(MonsterRole.SKELETON));
        assertEquals("伤害药水", PotionBrew.WOUNDING.potionName(MonsterRole.CREEPER));
    }

    @Test void aNegativeReplacesOnlyTheCommonDrawAtFixedStrength() {
        int negatives = PotionBrew.pool(PotionBrew.Grade.NEGATIVE, MonsterRole.ZOMBIE).size();
        // Epic draw (weighted pick: invisibility owns rolls 0 and 1; no level roll, seconds), rare draw (index, level,
        // seconds), then the negative roll (149 < 150) with its pick and seconds.
        int rare = PotionBrew.pool(PotionBrew.Grade.RARE, MonsterRole.ZOMBIE).indexOf(PotionBrew.STRENGTH);
        int slowness = PotionBrew.pool(PotionBrew.Grade.NEGATIVE, MonsterRole.ZOMBIE).indexOf(PotionBrew.SLOWNESS);
        var doses = PotionBox.EPIC.draw(RULES, MonsterRole.ZOMBIE,
                script(1, 90, rare, 0, 30, 149, slowness, 25));
        // Epic rolls 180..300: invisibility is cut to two minutes, strength keeps 180 + 30.
        assertEquals(List.of(new PotionBox.Dose(PotionBrew.INVISIBILITY, PotionBrew.Grade.EPIC, 0, 120),
                new PotionBox.Dose(PotionBrew.STRENGTH, PotionBrew.Grade.RARE, 1, 210),
                new PotionBox.Dose(PotionBrew.SLOWNESS, PotionBrew.Grade.NEGATIVE, 0, 45)), doses);
        assertTrue(negatives > slowness);
    }

    @Test void theCommonDrawStaysWhenTheNegativeRollMisses() {
        int swiftness = PotionBrew.pool(PotionBrew.Grade.COMMON, MonsterRole.CREEPER).indexOf(PotionBrew.SWIFTNESS);
        var doses = PotionBox.COMMON.draw(RULES, MonsterRole.CREEPER, script(150, swiftness, 100, 45));
        assertEquals(List.of(new PotionBox.Dose(PotionBrew.SWIFTNESS, PotionBrew.Grade.COMMON, 0, 135)), doses);
        int wounding = PotionBrew.pool(PotionBrew.Grade.NEGATIVE, MonsterRole.CREEPER).indexOf(PotionBrew.WOUNDING);
        assertEquals(List.of(new PotionBox.Dose(PotionBrew.WOUNDING, PotionBrew.Grade.NEGATIVE, 0, 0)),
                PotionBox.COMMON.draw(RULES, MonsterRole.CREEPER, script(0, wounding)));
    }

    @Test void drawnDurationsStayInTheirBoxRange() {
        var random = new Random(11);
        for (int i = 0; i < 3_000; i++) {
            for (var box : PotionBox.values()) {
                int min = (int) (double) RULES.combatValues().get(box.minSecondsKey());
                int max = (int) (double) RULES.combatValues().get(box.maxSecondsKey());
                for (var role : MonsterRole.values()) for (var dose : box.draw(RULES, role, random)) {
                    var brew = dose.brew();
                    assertTrue(dose.seconds() <= PotionBox.MAX_SECONDS, dose.toString());
                    if (brew.instant() || brew.untilDeath()) continue;
                    if (dose.grade() == PotionBrew.Grade.NEGATIVE) assertTrue(dose.seconds() >= 20 && dose.seconds() <= 45, dose.toString());
                    else if (brew == PotionBrew.TURTLE_MASTER) assertEquals(PotionBrew.TURTLE_MASTER_SECONDS, dose.seconds());
                    else if (brew == PotionBrew.INVISIBILITY) assertEquals(PotionBrew.INVISIBILITY_MAX_SECONDS, dose.seconds(),
                            "The epic range starts above two minutes, so every invisibility is cut to exactly that");
                    else assertTrue(dose.seconds() >= min && dose.seconds() <= max, box + " " + dose);
                }
            }
        }
        assertEquals(300, PotionBox.MAX_SECONDS);
        assertEquals(120, PotionBrew.INVISIBILITY_MAX_SECONDS);
    }

    @Test void partingGiftsLastUntilDeathAndSpendNoSecondsRoll() {
        assertEquals(java.util.Set.of(PotionBrew.WEAVING, PotionBrew.OOZING, PotionBrew.INFESTED, PotionBrew.WIND_CHARGED),
                java.util.Arrays.stream(PotionBrew.values()).filter(PotionBrew::untilDeath).collect(java.util.stream.Collectors.toSet()));
        // Epic roll 2 is weaving (invisibility holds 0 and 1). A gift draws neither a level nor seconds; had it asked for
        // them the next scripted answers would be shifted and rejected as out of bounds.
        int absorption = PotionBrew.pool(PotionBrew.Grade.RARE, MonsterRole.ZOMBIE).indexOf(PotionBrew.ABSORPTION);
        var doses = PotionBox.EPIC.draw(RULES, MonsterRole.ZOMBIE, script(2, absorption, 999, 7, 999, 0, 999, 0));
        assertEquals(new PotionBox.Dose(PotionBrew.WEAVING, PotionBrew.Grade.EPIC, 0, PotionBox.UNTIL_DEATH), doses.get(0));
        assertEquals(new PotionBox.Dose(PotionBrew.ABSORPTION, PotionBrew.Grade.RARE, 0, 187), doses.get(1));
        assertEquals(3, doses.size());
        for (var gift : List.of(PotionBrew.OOZING, PotionBrew.INFESTED, PotionBrew.WIND_CHARGED)) {
            int roll = PotionBrew.pool(PotionBrew.Grade.EPIC, MonsterRole.SKELETON).indexOf(gift) + 1;
            assertEquals(PotionBox.UNTIL_DEATH, PotionBox.EPIC.draw(RULES, MonsterRole.SKELETON,
                    script(roll, 0, 999, 0, 999, 0, 999, 0)).get(0).seconds(), gift.toString());
        }
    }

    @Test void invisibilityOwnsTwoOfTheEpicPoolsSixRolls() {
        var bounds = new ArrayList<Integer>();
        for (var role : MonsterRole.values()) {
            var expected = List.of(PotionBrew.INVISIBILITY, PotionBrew.INVISIBILITY, PotionBrew.WEAVING, PotionBrew.OOZING,
                    PotionBrew.INFESTED, PotionBrew.WIND_CHARGED);
            for (int roll = 0; roll < 6; roll++) {
                final int pinned = roll;
                var recording = new RandomGenerator() {
                    boolean first = true;
                    @Override public long nextLong() { throw new AssertionError("unscripted draw"); }
                    @Override public int nextInt(int bound) {
                        if (first) { first = false; bounds.add(bound); return pinned; }
                        return 0; // the rest of the box: first of each pool, no level II, shortest time
                    }
                };
                // The first draw of an epic box is its epic pick, so the pinned roll lands on the weighted pick.
                assertEquals(expected.get(roll), PotionBox.EPIC.draw(RULES, role, recording).get(0).brew(), role + " roll " + roll);
            }
        }
        assertEquals(java.util.Set.of(6), new java.util.HashSet<>(bounds), "Total weight is 2 + 4 x 1");
        assertEquals(2, PotionBrew.INVISIBILITY.weight());
        assertEquals(6, PotionBrew.pool(PotionBrew.Grade.EPIC, MonsterRole.ZOMBIE).stream().mapToInt(PotionBrew::weight).sum());
        for (var brew : PotionBrew.values()) if (brew != PotionBrew.INVISIBILITY) assertEquals(1, brew.weight(), brew.toString());
    }

    @Test void aDoseMustMatchHowItsEffectLasts() {
        assertEquals(PotionBox.UNTIL_DEATH, new PotionBox.Dose(PotionBrew.INFESTED, PotionBrew.Grade.EPIC, 0, PotionBox.UNTIL_DEATH).seconds());
        assertThrows(IllegalArgumentException.class, () -> new PotionBox.Dose(PotionBrew.OOZING, PotionBrew.Grade.EPIC, 0, 60));
        assertThrows(IllegalArgumentException.class, () -> new PotionBox.Dose(PotionBrew.WIND_CHARGED, PotionBrew.Grade.EPIC, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PotionBox.Dose(PotionBrew.SWIFTNESS, PotionBrew.Grade.COMMON, 0, PotionBox.UNTIL_DEATH));
        assertThrows(IllegalArgumentException.class, () -> new PotionBox.Dose(PotionBrew.MENDING, PotionBrew.Grade.RARE, 0, PotionBox.UNTIL_DEATH));
        assertThrows(IllegalArgumentException.class, () -> new PotionBox.Dose(PotionBrew.SWIFTNESS, PotionBrew.Grade.COMMON, 0, PotionBox.MAX_SECONDS + 1));
        assertEquals(300, new PotionBox.Dose(PotionBrew.SWIFTNESS, PotionBrew.Grade.COMMON, 0, 300).seconds());
    }

    @Test void instantHealthIsFlatAndOldRoundsStockNoBoxes() {
        var wallet = MonsterWallet.EMPTY.credit(20).chooseRole(RULES, MonsterRole.ZOMBIE)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.INSTANT_HEALTH, 4)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.INSTANT_HEALTH, 4);
        assertEquals(12, wallet.coins());
        assertTrue(wallet.soldOut(RULES, ShopOffer.INSTANT_HEALTH));
        assertEquals(4, MonsterCoins.refund(RULES, ShopOffer.INSTANT_HEALTH, 2, 1));
        assertEquals(36, MonsterCoins.price(RULES, ShopOffer.RARE_POTION_BOX, 1));

        var values = new TreeMap<>(RULES.combatValues());
        values.keySet().removeIf(key -> key.startsWith("coin.potionBox."));
        var old = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        assertFalse(MonsterCoins.offered(old, ShopOffer.EPIC_POTION_BOX));
        assertTrue(MonsterCoins.offered(old, ShopOffer.ABSORPTION));
    }

    @Test void boxSecondsUpToTheCapAreAccepted() {
        var values = new TreeMap<>(RULES.combatValues());
        values.put("coin.potionBox.epicMinSeconds", 300.0);
        values.put("coin.potionBox.epicMaxSeconds", 300.0);
        assertDoesNotThrow(() -> new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis()));
    }

    @Test void boxValuesAreValidated() {
        for (var bad : List.of(Map.of("coin.potionBox.negativePermille", 1001.0),
                Map.of("coin.potionBox.epicMaxSeconds", 301.0),
                Map.of("coin.potionBox.rareMinSeconds", 250.0))) {
            var values = new TreeMap<>(RULES.combatValues()); values.putAll(bad);
            assertThrows(IllegalArgumentException.class, () -> new RulesConfig(RULES.schemaVersion(), RULES.mode(),
                    RULES.minecraftVersion(), RULES.difficulty(), RULES.generationRule(), values,
                    RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis()), bad.toString());
        }
    }

    @Test void pendingRulesDropRegenerationAndRetuneFireResistance() {
        var values = new TreeMap<>(RULES.combatValues());
        values.put(RulesConfig.DEFAULTS_REVISION_KEY, 4.0);
        values.put("coin.price.fireResistance", 6.0);
        values.put("coin.price.regeneration", 8.0); values.put("coin.limit.regeneration", 1.0);
        values.put("coin.potion.regenerationSeconds", 45.0);
        var stored = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        var migrated = stored.withEconomyDefaults();
        assertEquals(5.0, migrated.combatValues().get("coin.price.fireResistance"));
        assertFalse(migrated.combatValues().containsKey("coin.price.regeneration"));
        assertFalse(migrated.combatValues().containsKey("coin.potion.regenerationSeconds"));
        assertTrue(ShopOffer.retired("regeneration"));
    }

    private static RulesConfig stored(Map<String, Double> overrides) {
        var values = new TreeMap<>(RULES.combatValues());
        values.put(RulesConfig.DEFAULTS_REVISION_KEY, 8.0);
        values.putAll(overrides);
        return new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
    }

    private static final Map<String, Double> OLD_DURATIONS = Map.ofEntries(
            Map.entry("coin.potion.swiftnessSeconds", 180.0), Map.entry("coin.potion.fireResistanceSeconds", 180.0),
            Map.entry("coin.potion.absorptionSeconds", 120.0), Map.entry("coin.potion.resistanceSeconds", 90.0),
            Map.entry("coin.potionBox.commonMinSeconds", 45.0), Map.entry("coin.potionBox.commonMaxSeconds", 90.0),
            Map.entry("coin.potionBox.rareMinSeconds", 60.0), Map.entry("coin.potionBox.rareMaxSeconds", 120.0),
            Map.entry("coin.potionBox.epicMinSeconds", 90.0), Map.entry("coin.potionBox.epicMaxSeconds", 180.0));

    @Test void revisionNineMovesEveryOldDurationDefaultToTheNewOne() {
        var migrated = stored(OLD_DURATIONS).withEconomyDefaults();
        OLD_DURATIONS.keySet().forEach(key -> assertEquals(RULES.combatValues().get(key), migrated.combatValues().get(key), key));
        assertEquals(300.0, migrated.combatValues().get("coin.potion.swiftnessSeconds"));
        assertEquals(180.0, migrated.combatValues().get("coin.potion.resistanceSeconds"));
        assertEquals(240.0, migrated.combatValues().get("coin.potionBox.rareMaxSeconds"));
        assertEquals(9.0, migrated.combatValues().get(RulesConfig.DEFAULTS_REVISION_KEY));
        assertEquals(migrated, migrated.withEconomyDefaults(), "Migrated rules are stable");
    }

    @Test void revisionNineKeepsWhatAnOperatorChanged() {
        var overrides = new TreeMap<>(OLD_DURATIONS);
        overrides.put("coin.potion.swiftnessSeconds", 200.0);
        overrides.put("coin.potion.absorptionSeconds", 150.0);
        var migrated = stored(overrides).withEconomyDefaults();
        assertEquals(200.0, migrated.combatValues().get("coin.potion.swiftnessSeconds"));
        assertEquals(150.0, migrated.combatValues().get("coin.potion.absorptionSeconds"));
        assertEquals(300.0, migrated.combatValues().get("coin.potion.fireResistanceSeconds"));
        assertEquals(180.0, migrated.combatValues().get("coin.potion.resistanceSeconds"));
    }

    @Test void revisionNineMovesABoxRangeOnlyWhenBothEndsAreStillTheOldDefaults() {
        // The operator shortened the common box's top end only: moving the bottom to 90 would put it above 80 and
        // the rules would refuse to load. Both ends stay; the untouched boxes still move together.
        var overrides = new TreeMap<>(OLD_DURATIONS);
        overrides.put("coin.potionBox.commonMaxSeconds", 80.0);
        overrides.put("coin.potionBox.rareMinSeconds", 70.0);
        var migrated = assertDoesNotThrow(() -> stored(overrides).withEconomyDefaults());
        assertEquals(45.0, migrated.combatValues().get("coin.potionBox.commonMinSeconds"));
        assertEquals(80.0, migrated.combatValues().get("coin.potionBox.commonMaxSeconds"));
        assertEquals(70.0, migrated.combatValues().get("coin.potionBox.rareMinSeconds"));
        assertEquals(120.0, migrated.combatValues().get("coin.potionBox.rareMaxSeconds"));
        assertEquals(180.0, migrated.combatValues().get("coin.potionBox.epicMinSeconds"));
        assertEquals(300.0, migrated.combatValues().get("coin.potionBox.epicMaxSeconds"));
        assertEquals(migrated, migrated.withEconomyDefaults(), "A kept range stays kept");
        // Rules written out and read back, as the pending rules are, still load.
        assertEquals(migrated, RulesConfig.decode(migrated.encode()));
    }
}
