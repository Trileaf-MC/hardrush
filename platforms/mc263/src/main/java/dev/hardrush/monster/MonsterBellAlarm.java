package dev.hardrush.monster;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** Lets vanilla's bell treat monster players as raiders (docs/plan/钟声显形.md). Server thread only. */
public final class MonsterBellAlarm {
    private MonsterBellAlarm() {}

    /** Whether a bell counts {@code entity} as a raider: any monster player that is not spectating. */
    public static boolean counts(LivingEntity entity) {
        return entity instanceof ServerPlayer player && !player.isSpectator() && MonsterRoles.isMonster(player);
    }
}
