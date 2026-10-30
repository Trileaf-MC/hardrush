package dev.hardrush.core.round;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;

/**
 * A deployed monster's worth: the experience its body sprays when a player slays it, shown to the
 * monster on its own experience bar. Worth grows with the sortie's bounty-weighted damage credit.
 */
public final class MonsterWorth {
    public static final String BASE_XP = "worth.baseXp";
    public static final String XP_PER_DAMAGE = "worth.xpPerDamage";
    public static final String MAX_XP = "worth.maxXp";
    public static final String BOOK_MIN_LEVEL = "worth.bookMinLevel";
    public static final String BOOK_COST_PER_LEVEL = "worth.bookCostPerLevel";

    private MonsterWorth() {}

    /** Rounds saved before worth existed keep playing without it. */
    public static boolean enabled(RulesConfig rules) {
        return rules.mode() == GameMode.HARDCORE && rules.combatValues().containsKey(BASE_XP);
    }

    /** Base experience plus each bounty-weighted damage point of this sortie, capped. */
    public static int experience(RulesConfig rules, MonsterWallet wallet) {
        long weightedMilli = 0;
        for (var credit : wallet.sortieDamage().values()) weightedMilli = Math.addExact(weightedMilli, credit.weightedMilli());
        long xp = amount(rules, BASE_XP) + Math.multiplyExact(weightedMilli, amount(rules, XP_PER_DAMAGE)) / 1000;
        return (int) Math.min(xp, amount(rules, MAX_XP));
    }

    /** The vanilla level a player reaches when collecting {@code xp} points from level 0. */
    public static int level(int xp) {
        int level = 0;
        for (int left = xp; left >= xpNeededForNextLevel(level); level++) left -= xpNeededForNextLevel(level);
        return level;
    }

    /** The vanilla experience bar fill within {@link #level(int)}. */
    public static float progress(int xp) {
        int left = xp;
        for (int level = 0; ; level++) {
            int needed = xpNeededForNextLevel(level);
            if (left < needed) return (float) left / needed;
            left -= needed;
        }
    }

    /** Enchanting cost of the slain monster's book, or 0 when its worth is below the book threshold. */
    public static int bookCost(RulesConfig rules, int xp) {
        int level = level(xp);
        return level < amount(rules, BOOK_MIN_LEVEL) ? 0 : Math.multiplyExact(level, amount(rules, BOOK_COST_PER_LEVEL));
    }

    /** Player.getXpNeededForNextLevel in Java 26.3. */
    static int xpNeededForNextLevel(int level) {
        if (level >= 30) return 112 + (level - 30) * 9;
        return level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
    }

    private static int amount(RulesConfig rules, String key) {
        Double value = rules.combatValues().get(key);
        if (value == null) throw new IllegalStateException("本轮规则缺少身价参数：" + key);
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }
}
