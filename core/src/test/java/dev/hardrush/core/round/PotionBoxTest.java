package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import java.util.ArrayDeque;
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
        // Epic draw (pool index, no level roll for invisibility, seconds), rare draw (index, level, seconds), then the
        // negative roll (149 < 150) with its pick and seconds.
        int epic = PotionBrew.pool(PotionBrew.Grade.EPIC, MonsterRole.ZOMBIE).indexOf(PotionBrew.INVISIBILITY);
        int rare = PotionBrew.pool(PotionBrew.Grade.RARE, MonsterRole.ZOMBIE).indexOf(PotionBrew.STRENGTH);
        int slowness = PotionBrew.pool(PotionBrew.Grade.NEGATIVE, MonsterRole.ZOMBIE).indexOf(PotionBrew.SLOWNESS);
        var doses = PotionBox.EPIC.draw(RULES, MonsterRole.ZOMBIE,
                script(epic, 90, rare, 0, 30, 149, slowness, 25));
        assertEquals(List.of(new PotionBox.Dose(PotionBrew.INVISIBILITY, PotionBrew.Grade.EPIC, 0, 60),
                new PotionBox.Dose(PotionBrew.STRENGTH, PotionBrew.Grade.RARE, 1, 120),
                new PotionBox.Dose(PotionBrew.SLOWNESS, PotionBrew.Grade.NEGATIVE, 0, 45)), doses);
        assertTrue(negatives > slowness);
    }

    @Test void theCommonDrawStaysWhenTheNegativeRollMisses() {
        int swiftness = PotionBrew.pool(PotionBrew.Grade.COMMON, MonsterRole.CREEPER).indexOf(PotionBrew.SWIFTNESS);
        var doses = PotionBox.COMMON.draw(RULES, MonsterRole.CREEPER, script(150, swiftness, 100, 45));
        assertEquals(List.of(new PotionBox.Dose(PotionBrew.SWIFTNESS, PotionBrew.Grade.COMMON, 0, 90)), doses);
        int wounding = PotionBrew.pool(PotionBrew.Grade.NEGATIVE, MonsterRole.CREEPER).indexOf(PotionBrew.WOUNDING);
        assertEquals(List.of(new PotionBox.Dose(PotionBrew.WOUNDING, PotionBrew.Grade.NEGATIVE, 0, 0)),
                PotionBox.COMMON.draw(RULES, MonsterRole.CREEPER, script(0, wounding)));
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

    @Test void boxValuesAreValidated() {
        for (var bad : List.of(Map.of("coin.potionBox.negativePermille", 1001.0),
                Map.of("coin.potionBox.epicMaxSeconds", 181.0),
                Map.of("coin.potionBox.rareMinSeconds", 130.0))) {
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
}
