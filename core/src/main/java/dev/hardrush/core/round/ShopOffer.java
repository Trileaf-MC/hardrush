package dev.hardrush.core.round;

import java.util.EnumSet;
import java.util.Set;

/**
 * Witch merchant offers. Prices and limits come from the round's immutable rules; a round that
 * predates an offer simply does not stock it. Declaration order is the witch's shelf order, and the camp backpack's:
 * the native trading screen shows seven offers before it scrolls. Stored rounds name offers by id, never by ordinal.
 */
public enum ShopOffer {
    ADULT_EGG("adultEgg", "成年僵尸刷怪蛋", Kind.CARRIED, MonsterRole.ZOMBIE),
    BABY_EGG("babyEgg", "幼年僵尸刷怪蛋", Kind.CARRIED, MonsterRole.ZOMBIE),
    // What it lets out depends on the soulcatcher's tier (docs/plan/怪物盲盒.md); rounds without a soulcatcher never stock it.
    MONSTER_BOX("monsterBox", "怪物盲盒", Kind.CARRIED, MonsterRole.ZOMBIE),
    // Witch potions (docs/plan/女巫药水与药水盲盒.md) ride the sortie as bound bottles the monster drinks itself; the blind
    // boxes lead them so that every form sees them on the trading screen's first page.
    COMMON_POTION_BOX("commonPotionBox", "普通药水盲盒", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    RARE_POTION_BOX("rarePotionBox", "稀有药水盲盒", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    EPIC_POTION_BOX("epicPotionBox", "史诗药水盲盒", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    SWIFTNESS("swiftness", "迅捷药水", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    FIRE_RESISTANCE("fireResistance", "抗火药水", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    // Native undead heal from harming, so the undead get a potion of harming and the creeper one of healing.
    INSTANT_HEALTH("instantHealth", "伤害／治疗药水", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    ABSORPTION("absorption", "伤害吸收药水", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    RESISTANCE("resistance", "抗性提升药水", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    TURTLE_MASTER("turtleMaster", "神龟药水", Kind.CARRIED, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
    // The creeper is poisoned too, and its explosion turns the poison into a lingering cloud.
    POISON("poison", "剧毒药水", Kind.CARRIED, MonsterRole.CREEPER),
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
    COAL("coal", "煤炭 ×8", Kind.REVIVAL, MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER),
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
    /**
     * Paid creeper state (docs/plan/苦力怕付费解锁.md): kept through returns, role changes and disconnects, and spent
     * only when a sortie carrying it is used up by the monster's death.
     */
    public boolean keptUntilUsed() { return this == CHARGED; }
    /**
     * Every purchase costs the base price: the doubling ladder skips it. Vault keys each open a single random piece, so
     * the camp's gear cap, not a rising price, bounds them; a potion of harming or healing is a staple bought by the bottle.
     * Bread and coal are staples of the revived life, and each longer blessing adds the same time as the last.
     */
    public boolean flatPrice() { return key() || this == INSTANT_HEALTH || this == BREAD || this == COAL || this == LONGER_BLESSING; }
    /** Whether a sortie as {@code role} carries it; revival supplies never ride a sortie. */
    public boolean sellableTo(MonsterRole role) { return !revival() && roles.contains(role); }
    /** A bottle the witch brews, drunk by the monster itself on its next sortie (docs/plan/女巫药水与药水盲盒.md). */
    public boolean potion() {
        return switch (this) {
            case SWIFTNESS, FIRE_RESISTANCE, INSTANT_HEALTH, ABSORPTION, RESISTANCE, TURTLE_MASTER, POISON,
                 COMMON_POTION_BOX, RARE_POTION_BOX, EPIC_POTION_BOX -> true;
            default -> false;
        };
    }
    /** The blind box this offer is, or empty for every other offer. */
    public java.util.Optional<PotionBox> potionBox() {
        return switch (this) {
            case COMMON_POTION_BOX -> java.util.Optional.of(PotionBox.COMMON);
            case RARE_POTION_BOX -> java.util.Optional.of(PotionBox.RARE);
            case EPIC_POTION_BOX -> java.util.Optional.of(PotionBox.EPIC);
            default -> java.util.Optional.empty();
        };
    }
    public String priceKey() { return "coin.price." + id; }
    public String limitKey() { return "coin.limit." + id; }

    /** Offers taken off the shelf; a stored count of one is dropped when an older round is read. */
    public static boolean retired(String id) { return id.equals("regeneration"); }

    public static ShopOffer fromId(String id) {
        for (var offer : values()) if (offer.id.equals(id)) return offer;
        throw new IllegalArgumentException("Unknown shop offer: " + id);
    }
}
