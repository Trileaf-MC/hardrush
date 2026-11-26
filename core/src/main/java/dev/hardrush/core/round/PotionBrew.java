package dev.hardrush.core.round;

import java.util.List;
import java.util.Set;

/**
 * What a {@linkplain PotionBox blind box} can brew, by pool. Each form only draws what works on it natively:
 * the undead heal from harming and are hurt by healing, a creeper has no hands to dig or strike with, and only a
 * creeper benefits from regeneration.
 */
public enum PotionBrew {
    SWIFTNESS(Grade.COMMON, "迅捷药水", "迅捷药水", true, Roles.ALL),
    LEAPING(Grade.COMMON, "跳跃药水", "跳跃药水", true, Roles.ALL),
    FIRE_RESISTANCE(Grade.COMMON, "抗火药水", "抗火药水", false, Roles.ALL),
    NIGHT_VISION(Grade.COMMON, "夜视药水", "夜视药水", false, Roles.ALL),
    SLOW_FALLING(Grade.COMMON, "缓降药水", "缓降药水", false, Roles.ALL),
    HASTE(Grade.COMMON, "急迫药水", "急迫药水", true, Roles.HANDED),

    STRENGTH(Grade.RARE, "力量药水", "力量药水", true, Roles.HANDED),
    RESISTANCE(Grade.RARE, "抗性提升药水", "抗性提升药水", true, Roles.ALL),
    ABSORPTION(Grade.RARE, "伤害吸收药水", "伤害吸收药水", true, Roles.ALL),
    /** Healing for whoever drinks it: harming for the undead, healing for a creeper. */
    MENDING(Grade.RARE, "伤害药水", "治疗药水", true, Roles.ALL),
    REGENERATION(Grade.RARE, "再生药水", "再生药水", true, Roles.CREEPER),
    TURTLE_MASTER(Grade.RARE, "神龟药水", "神龟药水", true, Roles.ALL),

    INVISIBILITY(Grade.EPIC, "隐身药水", "隐身药水", false, Roles.ALL),
    WEAVING(Grade.EPIC, "盘丝药水", "盘丝药水", false, Roles.ALL),
    OOZING(Grade.EPIC, "渗浆药水", "渗浆药水", false, Roles.ALL),
    INFESTED(Grade.EPIC, "寄生药水", "寄生药水", false, Roles.ALL),
    WIND_CHARGED(Grade.EPIC, "蓄风药水", "蓄风药水", false, Roles.ALL),

    SLOWNESS(Grade.NEGATIVE, "缓慢药水", "缓慢药水", false, Roles.ALL),
    WEAKNESS(Grade.NEGATIVE, "虚弱药水", "虚弱药水", false, Roles.ALL),
    GLOWING(Grade.NEGATIVE, "发光药水", "发光药水", false, Roles.ALL),
    NAUSEA(Grade.NEGATIVE, "反胃药水", "反胃药水", false, Roles.ALL),
    MINING_FATIGUE(Grade.NEGATIVE, "挖掘疲劳药水", "挖掘疲劳药水", false, Roles.HANDED),
    /** Hurts whoever drinks it, and may kill: healing for the undead, harming for a creeper. */
    WOUNDING(Grade.NEGATIVE, "治疗药水", "伤害药水", false, Roles.ALL);

    public enum Grade { COMMON, RARE, EPIC, NEGATIVE }

    private enum Roles {
        ALL(Set.of(MonsterRole.ZOMBIE, MonsterRole.SKELETON, MonsterRole.CREEPER)),
        HANDED(Set.of(MonsterRole.ZOMBIE, MonsterRole.SKELETON)),
        CREEPER(Set.of(MonsterRole.CREEPER));
        private final Set<MonsterRole> roles;
        Roles(Set<MonsterRole> roles) { this.roles = roles; }
    }

    /** Native invisibility for a whole minute is already strong; the turtle master keeps its native 20 seconds. */
    public static final int INVISIBILITY_MAX_SECONDS = 60;
    public static final int TURTLE_MASTER_SECONDS = 20;

    private final Grade grade;
    private final String undeadName;
    private final String creeperName;
    private final boolean leveled;
    private final Roles roles;

    PotionBrew(Grade grade, String undeadName, String creeperName, boolean leveled, Roles roles) {
        this.grade = grade; this.undeadName = undeadName; this.creeperName = creeperName; this.leveled = leveled; this.roles = roles;
    }

    public Grade grade() { return grade; }
    /** Whether a level II exists that does more; negatives are always level I. */
    public boolean leveled() { return leveled; }
    public boolean instant() { return this == MENDING || this == WOUNDING; }
    public boolean drawableBy(MonsterRole role) { return roles.roles.contains(role); }
    /** The native potion's name for this form, e.g. 伤害药水 for an undead drinking {@link #MENDING}. */
    public String potionName(MonsterRole role) { return role == MonsterRole.CREEPER ? creeperName : undeadName; }
    int fixedSeconds() { return this == TURTLE_MASTER ? TURTLE_MASTER_SECONDS : 0; }
    int maxSeconds() { return this == INVISIBILITY ? INVISIBILITY_MAX_SECONDS : PotionBox.MAX_SECONDS; }

    public static List<PotionBrew> pool(Grade grade, MonsterRole role) {
        return java.util.Arrays.stream(values()).filter(brew -> brew.grade == grade && brew.drawableBy(role)).toList();
    }
}
