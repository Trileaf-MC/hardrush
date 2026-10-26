package dev.hardrush.core.round;

import dev.hardrush.core.config.RulesConfig;
import java.util.Objects;

/** Stable persisted skill IDs; cooldown values come from this round's immutable rules. */
public enum MonsterSkill {
    // Historical snapshots retain this stable ID and any already recorded deadline.
    ZOMBIE_SUMMON("zombie.summon", MonsterRole.ZOMBIE, "zombie.summonCooldownMillis"),
    SKELETON_BURST("skeleton.burst", MonsterRole.SKELETON, "skeleton.burstCooldownMillis");

    private final String id;
    private final MonsterRole role;
    private final String cooldownKey;

    MonsterSkill(String id, MonsterRole role, String cooldownKey) {
        this.id = id; this.role = role; this.cooldownKey = cooldownKey;
    }

    public String id() { return id; }
    public MonsterRole role() { return role; }
    public boolean retired() { return this == ZOMBIE_SUMMON; }

    public long cooldownMillis(RulesConfig rules) {
        Double value = Objects.requireNonNull(rules).combatValues().get(cooldownKey);
        if (value == null) throw new IllegalStateException("本轮规则缺少技能冷却参数：" + cooldownKey);
        return value.longValue();
    }

    public static MonsterSkill fromId(String id) {
        for (var skill : values()) if (skill.id.equals(id)) return skill;
        throw new IllegalArgumentException("Unknown monster skill ID: " + id);
    }
}
