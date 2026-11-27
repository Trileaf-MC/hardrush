package dev.hardrush.core.round;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;

/**
 * A deployed monster's worth, kept in thousandths of a level and shown on its own experience bar. Worth starts at the
 * base level and grows with the coins the sortie carries (its investment) and its bounty-weighted damage credit. What a
 * slain body leaves for that worth is {@link MonsterSpoils}' business.
 */
public final class MonsterWorth {
    public static final String BASE_LEVEL = "worth.baseLevel";
    public static final String LEVELS_PER_DAMAGE = "worth.levelsPerDamage";
    public static final String MAX_LEVEL = "worth.maxLevel";
    public static final String XP_PER_LEVEL = "worth.xpPerLevel";
    public static final String BOOK_MIN_LEVEL = "worth.bookMinLevel";
    /** Coins of sortie investment per level of worth. */
    public static final String COINS_PER_LEVEL = "worth.coinsPerLevel";
    /** Thousandths of a round-long upgrade's price that each fresh sortie counts as investment. */
    public static final String PERMANENT_PERMILLE = "worth.permanentPermille";
    /** Levels of worth per iron ingot a slain body drops. */
    public static final String LEVELS_PER_IRON = "worth.levelsPerIron";

    private MonsterWorth() {}

    /** Rounds saved before worth existed keep playing without it. */
    public static boolean enabled(RulesConfig rules) {
        return rules.mode() == GameMode.HARDCORE && rules.combatValues().containsKey(BASE_LEVEL);
    }

    /** Rounds saved before kill spoils count no investment and drop neither iron nor brewing materials. */
    public static boolean spoilsEnabled(RulesConfig rules) {
        return enabled(rules) && rules.combatValues().containsKey(COINS_PER_LEVEL);
    }

    /**
     * Base level, plus levels for each bounty-weighted damage point of this sortie, plus a level per
     * {@link #COINS_PER_LEVEL} coins it carries, in milli-levels, capped.
     */
    public static int milliLevels(RulesConfig rules, MonsterWallet wallet) {
        long weightedMilli = 0;
        for (var credit : wallet.sortieDamage().values()) weightedMilli = Math.addExact(weightedMilli, credit.weightedMilli());
        long milli = Math.addExact(amount(rules, BASE_LEVEL) * 1000L, Math.multiplyExact(weightedMilli, amount(rules, LEVELS_PER_DAMAGE)));
        // Investment is in milli-coins, so dividing by coins per level leaves milli-levels.
        if (spoilsEnabled(rules)) milli = Math.addExact(milli, wallet.sortieInvestment() / amount(rules, COINS_PER_LEVEL));
        return (int) Math.min(milli, amount(rules, MAX_LEVEL) * 1000L);
    }

    /** The whole level shown on the experience bar. */
    public static int level(int milliLevels) { return milliLevels / 1000; }

    /** The bar fill toward the next level. */
    public static float progress(int milliLevels) { return milliLevels % 1000 / 1000f; }

    static int amount(RulesConfig rules, String key) {
        Double value = rules.combatValues().get(key);
        if (value == null) throw new IllegalStateException("本轮规则缺少身价参数：" + key);
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }
}
