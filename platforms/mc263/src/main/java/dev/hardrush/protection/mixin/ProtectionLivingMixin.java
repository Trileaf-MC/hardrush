package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class ProtectionLivingMixin {
    @Inject(method="hurtServer", at=@At("HEAD"), cancellable=true)
    private void hurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (ProtectionRuntime.rejectDamage((LivingEntity)(Object)this, source)) ci.setReturnValue(false);
    }
    @Inject(method="addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at=@At("HEAD"), cancellable=true)
    private void effect(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> ci) {
        if (ProtectionRuntime.rejectEffect((LivingEntity)(Object)this, effect, source)) ci.setReturnValue(false);
    }
    @Inject(method="forceAddEffect", at=@At("HEAD"), cancellable=true)
    private void forcedEffect(MobEffectInstance effect, Entity source, CallbackInfo ci) {
        if (ProtectionRuntime.rejectEffect((LivingEntity)(Object)this, effect, source)) ci.cancel();
    }
    @Inject(method="knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V", at=@At("HEAD"), cancellable=true)
    private void knockback(double force, double x, double z, DamageSource source, float scale, boolean blocked, CallbackInfo ci) {
        if (ProtectionRuntime.rejectDamage((LivingEntity)(Object)this, source)) ci.cancel();
    }
    @Inject(method="push(Lnet/minecraft/world/entity/Entity;)V", at=@At("HEAD"), cancellable=true)
    private void collision(Entity other, CallbackInfo ci) {
        if (ProtectionRuntime.protectedEntity((Entity)(Object)this) || ProtectionRuntime.protectedEntity(other)) ci.cancel();
    }
}
