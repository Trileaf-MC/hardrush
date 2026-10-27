package dev.hardrush.monster.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.hardrush.monster.MonsterClouds;
import dev.hardrush.combat.MonsterCombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AreaEffectCloud.class)
public abstract class MonsterCloudMixin {
    @WrapMethod(method = "serverTick")
    private void frozenEffectSource(ServerLevel level, Operation<Void> original) {
        try (var ignored = MonsterCombatRuntime.action((AreaEffectCloud)(Object)this)) { original.call(level); }
    }

    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;isAffectedByPotions()Z"))
    private boolean admittedContact(LivingEntity victim, Operation<Boolean> original) {
        return original.call(victim) && MonsterClouds.permits((AreaEffectCloud)(Object)this, victim);
    }
}
