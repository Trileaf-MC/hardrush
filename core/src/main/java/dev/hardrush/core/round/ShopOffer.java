package dev.hardrush.core.round;

import java.util.EnumSet;
import java.util.Set;

/**
 * Witch merchant offers. Prices and limits come from the round's immutable rules; a round that
 * predates an offer simply does not stock it.
 */
public enum ShopOffer {
    ADULT_EGG("adultEgg", "成年僵尸刷怪蛋", Kind.CARRIED, MonsterRole.ZOMBIE),
    BABY_EGG("babyEgg", "幼年僵尸刷怪蛋", Kind.CARRIED, MonsterRole.ZOMBIE),
    SWIFTNESS("swiftness", "迅捷药水", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    FIRE_RESISTANCE("fireResistance", "抗火药水", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    // Native undead ignore regeneration, so the witch only brews it for creepers.
    REGENERATION("regeneration", "再生药水", Kind.CARRIED, MonsterRole.CREEPER),
    SLOWNESS_ARROWS("slownessArrows", "缓慢药箭", Kind.CARRIED, MonsterRole.SKELETON),
    POISON_ARROWS("poisonArrows", "剧毒药箭", Kind.CARRIED, MonsterRole.SKELETON),
    CHARGED("charged", "带电出击", Kind.CARRIED, MonsterRole.CREEPER),
    TRIAL_KEY("trialKey", "试炼钥匙", Kind.KEY, MonsterRole.ZOMBIE, MonsterRole.SKELETON),
    OMINOUS_KEY("ominousKey", "不祥试炼钥匙", Kind.KEY, MonsterRole.ZOMBIE, MonsterRole.SKELETON),
    // Each purchase raises the soulcatcher one rarity, in order; the limit is the number of steps.
    SOULCATCHER_TIER("soulcatcherTier", "拘魂袋升阶", Kind.PERMANENT, MonsterRole.ZOMBIE),
    SOULCATCHER_CAPACITY("soulcatcherCapacity", "拘魂袋扩容", Kind.PERMANENT, MonsterRole.ZOMBIE);

    /** Carried goods ride the next sortie, keys open a camp vault, permanent upgrades last the whole round. */
    public enum Kind { CARRIED, KEY, PERMANENT }

    private final String id;
    private final String title;
    private final Kind kind;
    private final Set<MonsterRole> roles;

    ShopOffer(String id, String title, Kind kind, MonsterRole first, MonsterRole... rest) {
        this.id = id; this.title = title; this.kind = kind; this.roles = Set.copyOf(EnumSet.of(first, rest));
    }

    public String id() { return id; }
    public String title() { return title; }
    /** Keys are opened at a camp vault; every other offer is carried into the next sortie. */
    public boolean key() { return kind == Kind.KEY; }
    /** Never queued for a sortie: owned until revival or the next round, and never refunded. */
    public boolean permanent() { return kind == Kind.PERMANENT; }
    public Set<MonsterRole> roles() { return roles; }
    public boolean sellableTo(MonsterRole role) { return roles.contains(role); }
    public String priceKey() { return "coin.price." + id; }
    public String limitKey() { return "coin.limit." + id; }

    public static ShopOffer fromId(String id) {
        for (var offer : values()) if (offer.id.equals(id)) return offer;
        throw new IllegalArgumentException("Unknown shop offer: " + id);
    }
}
