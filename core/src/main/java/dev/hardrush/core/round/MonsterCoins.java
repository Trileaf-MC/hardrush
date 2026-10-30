package dev.hardrush.core.round;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;

/** Monster coin economy values, read from the round's immutable rules snapshot. */
public final class MonsterCoins {
    public static final String STARTER = "coin.starter";
    public static final String KILLED_BY_HUMAN = "coin.killedByHuman";
    public static final String DAMAGE_CAP = "coin.damageCapPerHuman";
    public static final String BOUNTY_NETHER = "coin.bounty.netherBonus";
    public static final String BOUNTY_END = "coin.bounty.endBonus";
    public static final String BOUNTY_SURVIVAL_1 = "coin.bounty.survivalBonus1";
    public static final String BOUNTY_SURVIVAL_2 = "coin.bounty.survivalBonus2";
    public static final String BOUNTY_MAX = "coin.bounty.max";
    public static final String SURVIVAL_TIER_1 = "coin.survival.tier1Minutes";
    public static final String SURVIVAL_TIER_2 = "coin.survival.tier2Minutes";
    public static final String ARROWS_PER_BUNDLE = "coin.arrowsPerBundle";
    public static final String SWIFTNESS_SECONDS = "coin.potion.swiftnessSeconds";
    public static final String FIRE_RESISTANCE_SECONDS = "coin.potion.fireResistanceSeconds";
    public static final String REGENERATION_SECONDS = "coin.potion.regenerationSeconds";
    /** One trade slot holds one native emerald stack. */
    public static final int MAX_PRICE = 64;

    private MonsterCoins() {}

    /** Rounds saved before the economy existed keep playing without it. */
    public static boolean enabled(RulesConfig rules) {
        return rules.mode() == GameMode.HARDCORE && rules.combatValues().containsKey(STARTER);
    }

    public static int amount(RulesConfig rules, String key) {
        Double value = rules.combatValues().get(key);
        if (value == null) throw new IllegalStateException("本轮规则缺少怪物币参数：" + key);
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }

    public static int limit(RulesConfig rules, ShopOffer offer) { return amount(rules, offer.limitKey()); }

    /** Each further purchase within one sortie window doubles the price. */
    public static int price(RulesConfig rules, ShopOffer offer, int bought) {
        if (bought < 0) throw new IllegalArgumentException("Negative purchase count");
        return Math.multiplyExact(amount(rules, offer.priceKey()), 1 << Math.min(bought, 30));
    }

    /** Refunds unwind the most expensive (latest) purchases first. */
    public static int refund(RulesConfig rules, ShopOffer offer, int bought, int count) {
        if (count < 0 || count > bought) throw new IllegalArgumentException("Invalid refund count");
        int total = 0;
        for (int i = bought - count; i < bought; i++) total = Math.addExact(total, price(rules, offer, i));
        return total;
    }

    /**
     * Bounty multiplier in permille: 1 + the dimension bonus where the human stands + the survival
     * bonus of the human's current life, added rather than multiplied and capped.
     */
    public static int bountyPermille(RulesConfig rules, MonsterDimension dimension, long lifeNanos) {
        double bonus = switch (dimension) {
            case OVERWORLD -> 0;
            case NETHER -> value(rules, BOUNTY_NETHER);
            case END -> value(rules, BOUNTY_END);
        };
        long minutes = lifeNanos / 60_000_000_000L;
        if (minutes >= value(rules, SURVIVAL_TIER_2)) bonus += value(rules, BOUNTY_SURVIVAL_2);
        else if (minutes >= value(rules, SURVIVAL_TIER_1)) bonus += value(rules, BOUNTY_SURVIVAL_1);
        double max = rules.combatValues().getOrDefault(BOUNTY_MAX, Double.MAX_VALUE);
        return (int) Math.round(Math.min(1 + bonus, max) * 1000);
    }

    private static double value(RulesConfig rules, String key) {
        // Zero is not a legal combat value; an absent bonus or tier simply never applies.
        return rules.combatValues().getOrDefault(key, key.startsWith("coin.survival.") ? Double.MAX_VALUE : 0.0);
    }

    /** Player-facing multiplier text such as 1.5 or 1.25. */
    public static String multiplier(int permille) {
        return java.math.BigDecimal.valueOf(permille, 3).stripTrailingZeros().toPlainString();
    }
}
