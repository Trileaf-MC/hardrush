package dev.hardrush.core.round;

import dev.hardrush.core.config.RulesConfig;

/** Monster coin economy values, read from the round's immutable rules snapshot. */
public final class MonsterCoins {
    public static final String STARTER = "coin.starter";
    public static final String KILLED_BY_HUMAN = "coin.killedByHuman";
    public static final String DAMAGE_CAP = "coin.damageCapPerHuman";
    public static final String ARROWS_PER_BUNDLE = "coin.arrowsPerBundle";
    public static final String SWIFTNESS_SECONDS = "coin.potion.swiftnessSeconds";
    public static final String FIRE_RESISTANCE_SECONDS = "coin.potion.fireResistanceSeconds";
    public static final String REGENERATION_SECONDS = "coin.potion.regenerationSeconds";
    /** One trade slot holds one native emerald stack; a price above it spills into the second payment slot. */
    public static final int STACK_PRICE = 64;

    private MonsterCoins() {}

    /** Rounds saved before the economy existed keep playing without it. */
    public static boolean enabled(RulesConfig rules) {
        return rules.combatValues().containsKey(STARTER);
    }

    public static int amount(RulesConfig rules, String key) {
        Double value = rules.combatValues().get(key);
        if (value == null) throw new IllegalStateException("本轮规则缺少怪物币参数：" + key);
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }

    /** Base number of creatures a zombie's soulcatcher holds; rounds without it carry no soulcatcher. */
    public static final String SOULCATCHER_CAPACITY = "soulcatcher.capacity";

    public static boolean soulcatcher(RulesConfig rules) { return rules.combatValues().containsKey(SOULCATCHER_CAPACITY); }

    /** Rounds saved before an offer existed have neither its price nor its limit, and the witch never stocks it. */
    public static boolean offered(RulesConfig rules, ShopOffer offer) {
        return rules.combatValues().containsKey(offer.priceKey()) && rules.combatValues().containsKey(offer.limitKey());
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
}
