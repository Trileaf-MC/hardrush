package dev.hardrush.monster;

import dev.hardrush.protection.ProtectionRuntime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.phys.AABB;

/**
 * A human who strikes a zombified piglin player provokes the zombified piglins around it, as striking a native one
 * would (docs/plan/怪物环境变种.md §4). The player is no {@link ZombifiedPiglin}, so vanilla's own alert never runs for
 * it; this makes the one call vanilla would, and the provoked piglins carry it on natively. Companions and wild
 * piglins answer alike, and a provoked wild piglin stays wild: no owner, no credit, no cleanup.
 */
public final class MonsterPiglinAnger {
    /** ZombifiedPiglin.alertOthers: the vertical reach of the call. */
    private static final double ALERT_RANGE_Y = 10;
    /** ZombifiedPiglin.ALERT_INTERVAL is four to six seconds; a player body calls at most every four. */
    static final int ALERT_INTERVAL_TICKS = 80;
    private static final Map<UUID, Integer> NEXT_ALERT = new HashMap<>();
    private static boolean initialized;

    private MonsterPiglinAnger() {}

    static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerLivingEntityEvents.AFTER_DAMAGE.register((target, source, base, taken, blocked) -> {
            if (taken > 0 && target instanceof ServerPlayer player) provoked(player, source);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> NEXT_ALERT.clear());
    }

    /** {@code player} just took real damage from {@code source}. */
    static void provoked(ServerPlayer player, DamageSource source) {
        // Arrows and thrown projectiles name their shooter; only a human's hit counts, never a fellow monster's.
        if (MonsterRoles.currentForm(player) != MonsterForm.ZOMBIFIED_PIGLIN || !(source.getEntity() instanceof ServerPlayer human)
                || MonsterRoles.isMonster(human) || !(player.level() instanceof ServerLevel level) || !player.isAlive()
                || ProtectionRuntime.protectedEntity(player) || !MonsterRoles.legalTarget(player.getUUID(), level, human)
                || !player.hasLineOfSight(human)) return;
        var model = MonsterRoles.nativeModel(player);
        if (model == null) return;
        int now = level.getServer().getTickCount();
        Integer next = NEXT_ALERT.get(player.getUUID());
        if (next != null && now < next) return;
        NEXT_ALERT.put(player.getUUID(), now + ALERT_INTERVAL_TICKS);
        // The native call's reach is the caller's follow range: the zombie's, which the body's model carries.
        double within = model.getAttributeValue(Attributes.FOLLOW_RANGE);
        var area = AABB.unitCubeFromLowerCorner(player.position()).inflate(within, ALERT_RANGE_Y, within);
        for (var piglin : level.getEntitiesOfClass(ZombifiedPiglin.class, area, EntitySelector.NO_SPECTATORS))
            if (piglin.getTarget() == null && !piglin.isAlliedTo(human)) piglin.setTarget(human);
    }
}
