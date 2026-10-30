package dev.hardrush.core.round;

import java.util.EnumSet;
import java.util.Set;

/** Witch merchant offers. Prices and per-sortie limits come from the round's immutable rules. */
public enum ShopOffer {
    ADULT_EGG("adultEgg", "成年僵尸刷怪蛋", false, MonsterRole.ZOMBIE),
    BABY_EGG("babyEgg", "幼年僵尸刷怪蛋", false, MonsterRole.ZOMBIE),
    SWIFTNESS("swiftness", "迅捷药水", false, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    FIRE_RESISTANCE("fireResistance", "抗火药水", false, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    // Native undead ignore regeneration, so the witch only brews it for creepers.
    REGENERATION("regeneration", "再生药水", false, MonsterRole.CREEPER),
    SLOWNESS_ARROWS("slownessArrows", "缓慢药箭", false, MonsterRole.SKELETON),
    POISON_ARROWS("poisonArrows", "剧毒药箭", false, MonsterRole.SKELETON),
    CHARGED("charged", "带电出场", false, MonsterRole.CREEPER),
    TRIAL_KEY("trialKey", "试炼钥匙", true, MonsterRole.ZOMBIE, MonsterRole.SKELETON),
    OMINOUS_KEY("ominousKey", "不祥试炼钥匙", true, MonsterRole.ZOMBIE, MonsterRole.SKELETON);

    private final String id;
    private final String title;
    private final boolean key;
    private final Set<MonsterRole> roles;

    ShopOffer(String id, String title, boolean key, MonsterRole first, MonsterRole... rest) {
        this.id = id; this.title = title; this.key = key; this.roles = Set.copyOf(EnumSet.of(first, rest));
    }

    public String id() { return id; }
    public String title() { return title; }
    /** Keys are opened at a camp vault; every other offer is carried into the next sortie. */
    public boolean key() { return key; }
    public Set<MonsterRole> roles() { return roles; }
    public boolean sellableTo(MonsterRole role) { return roles.contains(role); }
    public String priceKey() { return "coin.price." + id; }
    public String limitKey() { return "coin.limit." + id; }

    public static ShopOffer fromId(String id) {
        for (var offer : values()) if (offer.id.equals(id)) return offer;
        throw new IllegalArgumentException("Unknown shop offer: " + id);
    }
}
