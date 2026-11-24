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
    SOULCATCHER_CAPACITY("soulcatcherCapacity", "拘魂袋扩容", Kind.PERMANENT, MonsterRole.ZOMBIE),
    // Revival supplies (docs/plan/复生补给与女巫说明.md): only a monster holding a revival totem is offered them.
    BONUS_CHEST("bonusChest", "奖励箱", Kind.REVIVAL, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    LONGER_BLESSING("longerBlessing", "延长庇护", Kind.REVIVAL, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    IRON_BUCKET("ironBucket", "铁桶", Kind.REVIVAL, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    OAK_LOGS("oakLogs", "橡木原木 ×5", Kind.REVIVAL, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    IRON_PICKAXE("ironPickaxe", "铁镐", Kind.REVIVAL, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    SHIELD("shield", "盾牌", Kind.REVIVAL, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    // A bell rung near monster players makes them glow (docs/plan/钟声显形.md); vanilla never crafts one.
    BELL("bell", "钟", Kind.REVIVAL, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    BREAD("bread", "面包 ×8", Kind.REVIVAL, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER);

    /**
     * Carried goods ride the next sortie, keys open a camp vault, permanent upgrades last the whole round, and
     * revival supplies go with the human life the altar starts.
     */
    public enum Kind { CARRIED, KEY, PERMANENT, REVIVAL }

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
    /** Bought with a revival totem in hand and delivered to the revived human, never to a sortie. */
    public boolean revival() { return kind == Kind.REVIVAL; }
    /** Revival supplies that take effect where the revived body lands rather than in its backpack. */
    public boolean landing() { return this == BONUS_CHEST || this == LONGER_BLESSING; }
    public Set<MonsterRole> roles() { return roles; }
    /** Whether a sortie as {@code role} carries it; revival supplies never ride a sortie. */
    public boolean sellableTo(MonsterRole role) { return !revival() && roles.contains(role); }
    public String priceKey() { return "coin.price." + id; }
    public String limitKey() { return "coin.limit." + id; }

    public static ShopOffer fromId(String id) {
        for (var offer : values()) if (offer.id.equals(id)) return offer;
        throw new IllegalArgumentException("Unknown shop offer: " + id);
    }
}
