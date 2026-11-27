package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.MonsterSpoils.Material;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class MonsterSpoilsTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    private static final Set<Material> DUSTS = EnumSet.of(Material.GLOWSTONE_DUST, Material.REDSTONE);

    /** Returns {@code values} in order and records each bound asked for. */
    private static final class Script implements IntUnaryOperator {
        final ArrayDeque<Integer> values;
        final List<Integer> bounds = new ArrayList<>();
        Script(Integer... values) { this.values = new ArrayDeque<>(Arrays.asList(values)); }
        @Override public int applyAsInt(int bound) {
            bounds.add(bound);
            return values.isEmpty() ? 0 : values.poll();
        }
    }

    private static MonsterSpoils.Spoils roll(int milliLevels, IntUnaryOperator random) {
        return MonsterSpoils.roll(RULES, milliLevels, random);
    }

    @Test void theBodySpraysTenExperiencePerWholeLevel() {
        assertEquals(100, MonsterSpoils.experience(RULES, 10_000));
        assertEquals(100, MonsterSpoils.experience(RULES, 10_999));
        assertEquals(300, MonsterSpoils.experience(RULES, 30_000));
        assertEquals(300, roll(30_000, bound -> 0).experience());
    }

    @Test void fromLevelFifteenTheBookIsEnchantedAtTheBarLevel() {
        assertEquals(0, MonsterSpoils.bookLevel(RULES, 10_000));
        assertEquals(0, MonsterSpoils.bookLevel(RULES, 14_999));
        assertEquals(15, MonsterSpoils.bookLevel(RULES, 15_000));
        assertEquals(22, MonsterSpoils.bookLevel(RULES, 22_500));
        assertEquals(30, MonsterSpoils.bookLevel(RULES, 30_000));
        assertEquals(22, roll(22_500, bound -> 0).bookLevel());
    }

    @Test void anIronIngotPerSevenWholeLevels() {
        // 9.999 never happens in play, since worth starts at level 10; it only pins the pure function's floor.
        for (var entry : List.of(Map.entry(9_999, 1), Map.entry(10_000, 1), Map.entry(13_999, 1), Map.entry(14_000, 2),
                Map.entry(27_000, 3), Map.entry(28_000, 4), Map.entry(30_000, 4))) {
            assertEquals(entry.getValue(), roll(entry.getKey(), bound -> 0).ironIngots(), entry.getKey() + " milli-levels");
        }
    }

    @Test void belowTwelveNoBrewingAndAtTwelveOneAwkwardBrewsBase() {
        var script = new Script();
        assertEquals(Map.of(), roll(11_999, script).brewing());
        assertEquals(Map.of(Material.NETHER_WART, 1, Material.GLASS_BOTTLE, 3), roll(12_000, script).brewing());
        assertEquals(Map.of(Material.NETHER_WART, 1, Material.GLASS_BOTTLE, 3), roll(17_999, script).brewing());
        assertEquals(List.of(), script.bounds, "no draw below level 18");
    }

    @Test void atEighteenOneCommonDraw() {
        var script = new Script(4);
        assertEquals(Map.of(Material.NETHER_WART, 1, Material.GLASS_BOTTLE, 3, Material.PUFFERFISH, 1), roll(18_000, script).brewing());
        assertEquals(List.of(6), script.bounds);
        var below = new Script(4);
        roll(23_999, below);
        assertEquals(List.of(6), below.bounds, "nothing rare below level 24");
    }

    @Test void atTwentyFourOneRareDrawAndOneOrTwoDusts() {
        var glowstone = new Script(0, 1, 0, 0);
        assertEquals(Map.of(Material.NETHER_WART, 1, Material.GLASS_BOTTLE, 3, Material.MAGMA_CREAM, 1,
                Material.PHANTOM_MEMBRANE, 1, Material.GLOWSTONE_DUST, 1), roll(24_000, glowstone).brewing());
        assertEquals(List.of(6, 3, 2, 2), glowstone.bounds);
        var redstone = new Script(5, 2, 1, 1);
        assertEquals(Map.of(Material.NETHER_WART, 1, Material.GLASS_BOTTLE, 3, Material.FERMENTED_SPIDER_EYE, 1,
                Material.BREEZE_ROD, 1, Material.REDSTONE, 2), roll(29_999, redstone).brewing());
        assertEquals(List.of(6, 3, 2, 2), redstone.bounds, "the top tier waits for level 30");
    }

    @Test void atThirtyASecondBaseAndCommonDrawInThatOrder() {
        var script = new Script(1, 0, 1, 0, 1);
        var brewing = roll(30_000, script).brewing();
        assertEquals(Map.of(Material.NETHER_WART, 2, Material.GLASS_BOTTLE, 6, Material.GLISTERING_MELON_SLICE, 2,
                Material.GHAST_TEAR, 1, Material.REDSTONE, 1), brewing);
        assertEquals(List.of(6, 3, 2, 2, 6), script.bounds);
        assertEquals(List.of(Material.NETHER_WART, Material.GLASS_BOTTLE, Material.GLISTERING_MELON_SLICE, Material.GHAST_TEAR,
                Material.REDSTONE), List.copyOf(brewing.keySet()), "materials iterate in enum order");
        assertThrows(UnsupportedOperationException.class, () -> brewing.put(Material.REDSTONE, 9));
        // Tiers are fixed whole levels: a body above 30 adds nothing more.
        assertEquals(List.of(6, 3, 2, 2, 6), drawBounds(45_000));
    }

    private static List<Integer> drawBounds(int milliLevels) {
        var script = new Script();
        roll(milliLevels, script);
        return script.bounds;
    }

    @Test void everyPossibleDrawComesFromItsOwnPool() {
        for (int level : new int[] {18, 24, 30}) {
            var bounds = drawBounds(level * 1000);
            int draws = bounds.size();
            int combinations = bounds.stream().reduce(1, (a, b) -> a * b);
            for (int combination = 0; combination < combinations; combination++) {
                int[] digits = new int[draws];
                int rest = combination;
                for (int i = 0; i < draws; i++) { digits[i] = rest % bounds.get(i); rest /= bounds.get(i); }
                var values = Arrays.stream(digits).boxed().toArray(Integer[]::new);
                var brewing = roll(level * 1000, new Script(values)).brewing();
                int base = level >= 30 ? 2 : 1;
                assertEquals(base, brewing.get(Material.NETHER_WART));
                assertEquals(3 * base, brewing.get(Material.GLASS_BOTTLE));
                assertEquals(level >= 30 ? 2 : 1, count(brewing, MonsterSpoils.COMMON), level + ": " + brewing);
                assertEquals(level >= 24 ? 1 : 0, count(brewing, MonsterSpoils.RARE), level + ": " + brewing);
                int dust = count(brewing, DUSTS);
                assertTrue(level >= 24 ? dust == 1 || dust == 2 : dust == 0, level + ": " + brewing);
                assertTrue(level < 24 || DUSTS.stream().filter(brewing::containsKey).count() == 1, "glowstone or redstone, not both");
                var known = EnumSet.of(Material.NETHER_WART, Material.GLASS_BOTTLE);
                known.addAll(MonsterSpoils.COMMON); known.addAll(MonsterSpoils.RARE); known.addAll(DUSTS);
                assertTrue(known.containsAll(brewing.keySet()));
            }
        }
    }

    private static int count(Map<Material, Integer> brewing, java.util.Collection<Material> pool) {
        return pool.stream().mapToInt(material -> brewing.getOrDefault(material, 0)).sum();
    }

    @Test void thePoolsKeepTheSpeedrunsMainLineOut() {
        // Issue #106: never blaze, ender, gold, obsidian, fire, bed or string; sugar, spider eyes, slime, cobwebs,
        // stone, gunpowder and turtle shells are left out on purpose; materials only, never finished potions.
        var forbidden = List.of("BLAZE", "ENDER", "PEARL", "GOLD_INGOT", "GOLD_NUGGET", "GOLD_BLOCK", "OBSIDIAN", "FLINT",
                "FIRE_CHARGE", "BED", "WOOL", "STRING", "POTION", "SLIME", "COBWEB", "GUNPOWDER", "TURTLE", "SUGAR");
        for (var material : Material.values()) {
            for (var word : forbidden) assertFalse(material.name().contains(word), material + " contains " + word);
            assertNotEquals("SPIDER_EYE", material.name());
            assertNotEquals("STONE", material.name());
        }
        assertEquals(6, MonsterSpoils.COMMON.size());
        assertEquals(3, MonsterSpoils.RARE.size());
        assertTrue(MonsterSpoils.COMMON.stream().noneMatch(MonsterSpoils.RARE::contains));
    }

    @Test void aRandomOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> roll(18_000, bound -> bound));
        assertThrows(IllegalArgumentException.class, () -> roll(18_000, bound -> -1));
    }
}
