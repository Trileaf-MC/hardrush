package dev.hardrush.monster.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.combat.MonsterCombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
public abstract class MonsterWeaponStabMixin {
    @WrapMethod(method = "stabAttack")
    private boolean nativeDamage(EquipmentSlot slot, Entity target, float damage, boolean dealsDamage,
                                 boolean knockback, boolean dismounts, Operation<Boolean> original) {
        var attacker = (LivingEntity) (Object) this;
        if (!MonsterRoles.isNativeBody(attacker)) return original.call(slot, target, damage, dealsDamage, knockback, dismounts);
        if (!(attacker.level() instanceof ServerLevel level) || attacker.getUUID().equals(target.getUUID())
                || !MonsterRoles.legalTarget(attacker.getUUID(), level, target) || MonsterCombatRuntime.reject(attacker, target)) return false;
        try (var ignored = MonsterCombatRuntime.action(attacker)) {
            return original.call(slot, target, damage, dealsDamage, knockback, dismounts);
        }
    }
}
