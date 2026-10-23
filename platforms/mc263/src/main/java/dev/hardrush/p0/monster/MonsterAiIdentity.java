package dev.hardrush.p0.monster;

import java.util.List;
import java.util.function.Predicate;
import dev.hardrush.p0.monster.mixin.MonsterMobAccessor;
import dev.hardrush.p0.monster.mixin.ai.SpeciesRetaliationAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.phys.AABB;

/** Species predicates use an unregistered native body; world references always use its player. */
public final class MonsterAiIdentity {
    private MonsterAiIdentity() {}

    public static LivingEntity real(LivingEntity entity) {
        if (entity != null && entity.level() instanceof ServerLevel level
                && (entity instanceof ServerPlayer || entity instanceof Mob && level.getEntity(entity.getUUID()) != entity)) {
            var player = level.getServer().getPlayerList().getPlayer(entity.getUUID());
            if (player != null) return player;
            if (MonsterRoles.isNativeBody(entity)) return null;
        }
        return entity;
    }

    public static LivingEntity species(LivingEntity entity) {
        var actual = real(entity);
        if (actual != null) entity = actual;
        if (entity instanceof ServerPlayer player) {
            var model = MonsterRoles.nativeModel(player);
            if (model != null && model.level() == player.level()) {
                model.snapTo(player.position());
                model.setHealth(Math.min(player.getHealth(), model.getMaxHealth()));
                return model;
            }
        }
        return entity;
    }

    public static boolean matches(Class<?> type, LivingEntity entity) {
        return type.isInstance(species(entity));
    }

    public static boolean canAttack(LivingEntity attacker, LivingEntity target) {
        target = real(target);
        if (target == null || target.isRemoved() || target.level() != attacker.level() || !target.canBeSeenAsEnemy()) return false;
        // Ownership is object based in vanilla; a species template must not hide the owner.
        if (attacker instanceof TamableAnimal pet && pet.isOwnedBy(target)) return false;
        return attacker.canAttack(species(target));
    }

    public static <T extends LivingEntity> List<LivingEntity> candidates(ServerLevel level, Class<T> type, AABB area,
                                               Predicate<? super LivingEntity> predicate) {
        var result = new java.util.ArrayList<LivingEntity>(level.getEntitiesOfClass(type, area,
                candidate -> matches(type, candidate) && predicate.test(species(candidate))));
        // Keep vanilla's class-indexed query, adding only the players its physical class omitted.
        for (var player : level.players()) {
            if (!type.isInstance(player) && player.getBoundingBox().intersects(area)
                    && matches(type, player) && predicate.test(species(player))) result.add(player);
        }
        return result;
    }

    /** Body transitions invalidate cached automatic decisions, never the player's network identity. */
    public static void changed(ServerPlayer player) {
        for (var level : player.level().getServer().getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob)) continue;
                var target = mob.getTargetUnchecked();
                boolean revenge = mayRetaliate(mob, player);
                if (samePlayer(target, player) && (!revenge || !canAttack(mob, player))) {
                    mob.setTarget(null);
                    mob.getNavigation().stop();
                }
                var brain = mob.getBrain();
                if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                        && brain.getMemory(MemoryModuleType.ATTACK_TARGET).filter(value -> samePlayer(value, player)).isPresent()
                        && (!revenge || !canAttack(mob, player))) {
                    brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                    brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                }
                // These are sensor caches, rebuilt at the original sensor cadence.
                brain.eraseMemory(MemoryModuleType.NEAREST_PLAYERS);
                brain.eraseMemory(MemoryModuleType.NEAREST_VISIBLE_PLAYER);
                brain.eraseMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER);
                brain.eraseMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYERS);
                brain.eraseMemory(MemoryModuleType.NEAREST_TARGETABLE_PLAYER_NOT_WEARING_GOLD);
                brain.eraseMemory(MemoryModuleType.NEAREST_PLAYER_HOLDING_WANTED_ITEM);
                brain.eraseMemory(MemoryModuleType.NEAREST_HOSTILE);
                if (!revenge && mob instanceof NeutralMob neutral
                        && neutral.getPersistentAngerTarget() != null
                        && neutral.getPersistentAngerTarget().matches(player)) neutral.stopBeingAngry();
            }
        }
    }

    private static boolean samePlayer(LivingEntity entity, ServerPlayer player) {
        return entity != null && entity.getUUID().equals(player.getUUID());
    }

    private static boolean mayRetaliate(Mob mob, ServerPlayer player) {
        if (!canAttack(mob, player)) return false;
        if (mob instanceof TamableAnimal pet) {
            var owner = pet.getOwner();
            if (owner != null && (samePlayer(owner.getLastHurtByMob(), player) || samePlayer(owner.getLastHurtMob(), player))
                    && pet.wantsToAttack(player, owner)) return true;
        }
        if (!samePlayer(mob.getLastHurtByMob(), player)) return false;
        var speciesClass = species(player).getClass();
        for (var goal : ((MonsterMobAccessor)mob).hardrush$targetSelector().getAvailableGoals()) {
            if (!(goal.getGoal() instanceof HurtByTargetGoal hurt)) continue;
            boolean ignored = false;
            for (var type : ((SpeciesRetaliationAccessor)hurt).hardrush$ignoredAttackerClasses())
                if (type.isAssignableFrom(speciesClass)) { ignored = true; break; }
            if (!ignored) return true;
        }
        var brain = mob.getBrain();
        return brain.hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY)
                && brain.getMemory(MemoryModuleType.HURT_BY_ENTITY).filter(value -> samePlayer(value, player)).isPresent()
                || brain.hasMemoryValue(MemoryModuleType.ANGRY_AT)
                && brain.getMemory(MemoryModuleType.ANGRY_AT).filter(player.getUUID()::equals).isPresent();
    }
}
