package dev.hardrush.monster;

import dev.hardrush.combat.MonsterCombatRuntime;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;

/** A cloud's contact is admitted before effects, victim bookkeeping or radius consumption. */
public final class MonsterClouds {
    private static final String OWNER = "hardrush.cloud-owner=";
    private MonsterClouds() {}

    public static void bind(AreaEffectCloud cloud, UUID owner) { cloud.entityTags().add(OWNER + owner); }

    public static boolean permits(AreaEffectCloud cloud, LivingEntity target) {
        if (MonsterCombatRuntime.source(cloud).isPresent()) return !MonsterCombatRuntime.reject(cloud, target);
        for (String tag : cloud.entityTags()) if (tag.startsWith(OWNER)) {
            try {
                return cloud.level() instanceof ServerLevel level
                        && MonsterRoles.legalTarget(UUID.fromString(tag.substring(OWNER.length())), level, target)
                        && !MonsterCombatRuntime.reject(cloud, target);
            } catch (IllegalArgumentException malformed) { return false; }
        }
        return true; // Ordinary vanilla clouds retain their original contact rules.
    }
}
