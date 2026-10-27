package dev.hardrush.combat.mixin.monster;

import dev.hardrush.combat.MonsterCombatRuntime;
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
public abstract class MonsterCombatLivingMixin {
    @Inject(method="hurtServer", at=@At("HEAD"), cancellable=true)
    private void harm(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (MonsterCombatRuntime.rejectDamage((Entity)(Object)this, source)) ci.setReturnValue(false);
    }
    @Inject(method="addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at=@At("HEAD"), cancellable=true)
    private void effect(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> ci) {
        if (MonsterCombatRuntime.rejectEffect(source, (Entity)(Object)this, effect)) ci.setReturnValue(false);
    }
    @Inject(method="forceAddEffect", at=@At("HEAD"), cancellable=true)
    private void effect(MobEffectInstance effect, Entity source, CallbackInfo ci) {
        if (MonsterCombatRuntime.rejectEffect(source, (Entity)(Object)this, effect)) ci.cancel();
    }
    @Inject(method="knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V", at=@At("HEAD"), cancellable=true)
    private void knockback(double force, double x, double z, DamageSource source, float scale, boolean blocked, CallbackInfo ci) {
        if (MonsterCombatRuntime.rejectDamage((Entity)(Object)this, source)) ci.cancel();
    }
}
