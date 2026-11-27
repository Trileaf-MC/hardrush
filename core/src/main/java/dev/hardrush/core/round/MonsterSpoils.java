package dev.hardrush.core.round;

import dev.hardrush.core.config.RulesConfig;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * What a monster's slain body leaves for its worth (issue #106): the one reward table, apart from the native loot the
 * platform rolls. Experience and the signed book follow worth's whole level; iron and brewing materials only exist in
 * rounds whose rules {@linkplain MonsterWorth#spoilsEnabled enable spoils}.
 *
 * <p>Brewing materials help a speedrun only indirectly: the body leaves what a run rarely covers or takes long to get,
 * never what the main line itself needs. Whoever adds a material must keep these out of every pool:
 * <ul>
 *   <li>blaze powder and rods (eyes of ender and the brewing stand's only fuel), ender pearls and eyes of ender;</li>
 *   <li>gold ingots, nuggets and blocks (piglin barter for pearls): glistering melon and golden carrots come finished;</li>
 *   <li>obsidian, crying obsidian, flint and steel, fire charges; beds, wool and string.</li>
 * </ul>
 * Deliberately left out as well: sugar and spider eyes (too easy), slime, cobweb and stone (niche potions), gunpowder
 * (the creeper's native loot), turtle shells (armor in their own right).
 */
public final class MonsterSpoils {
    public enum Material {
        NETHER_WART, GLASS_BOTTLE, MAGMA_CREAM, GLISTERING_MELON_SLICE, GOLDEN_CARROT, RABBIT_FOOT, PUFFERFISH,
        FERMENTED_SPIDER_EYE, GHAST_TEAR, PHANTOM_MEMBRANE, BREEZE_ROD, GLOWSTONE_DUST, REDSTONE
    }

    public static final List<Material> COMMON = List.of(Material.MAGMA_CREAM, Material.GLISTERING_MELON_SLICE,
            Material.GOLDEN_CARROT, Material.RABBIT_FOOT, Material.PUFFERFISH, Material.FERMENTED_SPIDER_EYE);
    public static final List<Material> RARE = List.of(Material.GHAST_TEAR, Material.PHANTOM_MEMBRANE, Material.BREEZE_ROD);

    /** Brewing tiers are fixed whole levels, independent of the round's worth cap; nothing is added past the last. */
    static final int BASE_TIER = 12, COMMON_TIER = 18, RARE_TIER = 24, TOP_TIER = 30;

    /** {@code brewing} is unmodifiable and iterates in {@link Material} order. */
    public record Spoils(int experience, int bookLevel, int ironIngots, Map<Material, Integer> brewing) {
        public Spoils {
            Objects.requireNonNull(brewing);
            if (experience < 0 || bookLevel < 0 || ironIngots < 0) throw new IllegalArgumentException("Invalid spoils");
        }
    }

    private MonsterSpoils() {}

    /** Experience the slain body sprays: a fixed amount per whole level. */
    public static int experience(RulesConfig rules, int milliLevels) {
        return Math.multiplyExact(MonsterWorth.level(milliLevels), MonsterWorth.amount(rules, MonsterWorth.XP_PER_LEVEL));
    }

    /** Enchanting level of the slain monster's book, or 0 when its worth is below the book threshold. */
    public static int bookLevel(RulesConfig rules, int milliLevels) {
        int level = MonsterWorth.level(milliLevels);
        return level < MonsterWorth.amount(rules, MonsterWorth.BOOK_MIN_LEVEL) ? 0 : level;
    }

    /**
     * Everything the body leaves at {@code milliLevels}. {@code random.applyAsInt(bound)} returns a uniform value in
     * {@code [0, bound)}; it is drawn in a fixed order: the common pool at 18, then at 24 the rare pool, glowstone (0)
     * or redstone (1), and that dust's count less one, then the common pool again at 30.
     */
    public static Spoils roll(RulesConfig rules, int milliLevels, IntUnaryOperator random) {
        Objects.requireNonNull(random);
        int experience = experience(rules, milliLevels), book = bookLevel(rules, milliLevels);
        if (!MonsterWorth.spoilsEnabled(rules)) return new Spoils(experience, book, 0, Map.of());
        int level = MonsterWorth.level(milliLevels);
        int iron = level / MonsterWorth.amount(rules, MonsterWorth.LEVELS_PER_IRON);
        var brewing = new EnumMap<Material, Integer>(Material.class);
        if (level >= BASE_TIER) {
            add(brewing, Material.NETHER_WART, 1);
            add(brewing, Material.GLASS_BOTTLE, 3);
        }
        if (level >= COMMON_TIER) add(brewing, draw(COMMON, random), 1);
        if (level >= RARE_TIER) {
            add(brewing, draw(RARE, random), 1);
            var dust = draw(List.of(Material.GLOWSTONE_DUST, Material.REDSTONE), random);
            add(brewing, dust, 1 + pick(2, random));
        }
        if (level >= TOP_TIER) {
            add(brewing, Material.NETHER_WART, 1);
            add(brewing, Material.GLASS_BOTTLE, 3);
            add(brewing, draw(COMMON, random), 1);
        }
        return new Spoils(experience, book, iron, Collections.unmodifiableMap(brewing));
    }

    private static Material draw(List<Material> pool, IntUnaryOperator random) {
        return pool.get(pick(pool.size(), random));
    }

    private static int pick(int bound, IntUnaryOperator random) {
        int value = random.applyAsInt(bound);
        if (value < 0 || value >= bound) throw new IllegalArgumentException("Random value out of range: " + value);
        return value;
    }

    private static void add(Map<Material, Integer> brewing, Material material, int count) {
        brewing.merge(material, count, Integer::sum);
    }
}
