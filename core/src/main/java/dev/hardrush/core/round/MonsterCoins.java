package dev.hardrush.core.round;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;

/** Monster coin economy values, read from the round's immutable rules snapshot. */
public final class MonsterCoins {
    public static final String STARTER = "coin.starter";
    /** Rounds saved before stars decided it paid this flat amount for every monster a human killed. */
    public static final String KILLED_BY_HUMAN = "coin.killedByHuman";
    public static final String KILLED_BY_HUMAN_PREFIX = "coin.killedByHuman.star";
    public static final String HEAD_BOUNTY_PREFIX = "coin.headBounty.star";
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
        return rules.mode() == GameMode.HARDCORE && rules.combatValues().containsKey(STARTER);
    }

    public static int amount(RulesConfig rules, String key) {
        Double value = rules.combatValues().get(key);
        if (value == null) throw new IllegalStateException("本轮规则缺少怪物币参数：" + key);
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }

    /** Coins for a monster a human of {@code killerStars} killed (1 when no human is credited). */
    public static int killedByHuman(RulesConfig rules, int killerStars) {
        Double value = rules.combatValues().get(KILLED_BY_HUMAN_PREFIX + HuntPressure.tier(killerStars));
        return value != null ? (int) Math.round(value) : amount(rules, KILLED_BY_HUMAN);
    }

    /** Coins for killing, or forcing the totem of, a human of {@code stars}; nothing in rounds without head bounties. */
    public static int headBounty(RulesConfig rules, int stars) {
        Double value = rules.combatValues().get(HEAD_BOUNTY_PREFIX + HuntPressure.tier(stars));
        return value == null ? 0 : (int) Math.round(value);
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
