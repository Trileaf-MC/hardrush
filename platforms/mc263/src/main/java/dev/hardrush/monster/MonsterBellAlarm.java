package dev.hardrush.monster;

import dev.hardrush.core.hunt.BellAlarm;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;

/**
 * Lets vanilla's bell treat monster players as raiders (docs/plan/钟声显形.md). Vanilla's raider checks are static
 * and never see the bell, so {@link #enter}/{@link #exit} frame the server tick of the bell being asked about. A
 * reveal is recorded only when the tick ends: vanilla filters and glows one entity at a time, and resting the bell
 * at the first glow would leave the other monster players dark. Server thread only.
 */
public final class MonsterBellAlarm {
    private static final BellAlarm<BellBlockEntity> ALARM = new BellAlarm<>();
    private static BellBlockEntity ticking;
    private static boolean revealed;

    private MonsterBellAlarm() {}

    public static void enter(BellBlockEntity bell) { ticking = bell; revealed = false; }

    public static void exit() {
        if (revealed && ticking.getLevel() != null) ALARM.revealed(ticking, ticking.getLevel().getGameTime());
        ticking = null; revealed = false;
    }

    /** Whether the ticking bell counts {@code entity} as a raider: a monster player while the bell is not resting. */
    public static boolean counts(LivingEntity entity) {
        var bell = ticking;
        return bell != null && bell.getLevel() != null && entity instanceof ServerPlayer player && !player.isSpectator()
                && MonsterRoles.isMonster(player) && ALARM.ready(bell, bell.getLevel().getGameTime());
    }

    /** Vanilla is about to make {@code entity} glow. */
    public static void glowing(LivingEntity entity) { if (counts(entity)) revealed = true; }
}
