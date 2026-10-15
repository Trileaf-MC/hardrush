package dev.hardrush.p2.combat.mixin;

import dev.hardrush.p2.combat.P2CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnderDragon.class)
public abstract class MonsterCombatDragonMixin {
    @Inject(method="hurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;Lnet/minecraft/world/damagesource/DamageSource;F)Z", at=@At("HEAD"), cancellable=true)
    private void harm(ServerLevel level, EnderDragonPart part, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (P2CombatRuntime.rejectDamage((EnderDragon)(Object)this, source)) ci.setReturnValue(false);
    }
}
