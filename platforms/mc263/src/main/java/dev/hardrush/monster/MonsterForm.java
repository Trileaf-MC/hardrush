package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterRole;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;

/** A body can change species without changing the selected role or sortie. */
public enum MonsterForm {
    ZOMBIE(MonsterRole.ZOMBIE, EntityTypes.ZOMBIE), DROWNED(MonsterRole.ZOMBIE, EntityTypes.DROWNED),
    SKELETON(MonsterRole.SKELETON, EntityTypes.SKELETON), STRAY(MonsterRole.SKELETON, EntityTypes.STRAY),
    CREEPER(MonsterRole.CREEPER, EntityTypes.CREEPER);
    public final MonsterRole role;
    public final EntityType<? extends Mob> type;
    MonsterForm(MonsterRole role, EntityType<? extends Mob> type) { this.role = role; this.type = type; }
    public boolean zombie() { return role == MonsterRole.ZOMBIE; }
    public boolean skeleton() { return role == MonsterRole.SKELETON; }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
}
