package dev.hardrush.core.round;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;

/**
 * A deployed monster's worth, kept in thousandths of a level and shown on its own experience bar.
 * The whole level is the enchanting-table level of the book its slain body leaves; the body sprays
 * a fixed amount of experience per level. Worth grows with the sortie's bounty-weighted damage credit.
 */
public final class MonsterWorth {
    public static final String BASE_LEVEL = "worth.baseLevel";
    public static final String LEVELS_PER_DAMAGE = "worth.levelsPerDamage";
    public static final String MAX_LEVEL = "worth.maxLevel";
    public static final String XP_PER_LEVEL = "worth.xpPerLevel";
    public static final String BOOK_MIN_LEVEL = "worth.bookMinLevel";

    private MonsterWorth() {}

    /** Rounds saved before worth existed keep playing without it. */
    public static boolean enabled(RulesConfig rules) {
        return rules.mode() == GameMode.HARDCORE && rules.combatValues().containsKey(BASE_LEVEL);
    }

    /** Base level plus levels for each bounty-weighted damage point of this sortie, in milli-levels, capped. */
    public static int milliLevels(RulesConfig rules, MonsterWallet wallet) {
        long weightedMilli = 0;
        for (var credit : wallet.sortieDamage().values()) weightedMilli = Math.addExact(weightedMilli, credit.weightedMilli());
        long milli = amount(rules, BASE_LEVEL) * 1000L + Math.multiplyExact(weightedMilli, amount(rules, LEVELS_PER_DAMAGE));
        return (int) Math.min(milli, amount(rules, MAX_LEVEL) * 1000L);
    }

    /** The whole level shown on the experience bar. */
    public static int level(int milliLevels) { return milliLevels / 1000; }

    /** The bar fill toward the next level. */
    public static float progress(int milliLevels) { return milliLevels % 1000 / 1000f; }

    /** Experience the slain body sprays: a fixed amount per whole level. */
    public static int experience(RulesConfig rules, int milliLevels) {
        return Math.multiplyExact(level(milliLevels), amount(rules, XP_PER_LEVEL));
    }

    /** Enchanting level of the slain monster's book, or 0 when its worth is below the book threshold. */
    public static int bookLevel(RulesConfig rules, int milliLevels) {
        int level = level(milliLevels);
        return level < amount(rules, BOOK_MIN_LEVEL) ? 0 : level;
    }

    private static int amount(RulesConfig rules, String key) {
        Double value = rules.combatValues().get(key);
        if (value == null) throw new IllegalStateException("本轮规则缺少身价参数：" + key);
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }
}
