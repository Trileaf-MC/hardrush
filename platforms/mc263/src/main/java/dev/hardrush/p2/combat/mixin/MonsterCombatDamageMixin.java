package dev.hardrush.p2.combat.mixin;

import dev.hardrush.p2.combat.P2CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Crystal has no LivingEntity damage event; ServerPlayer performs work before calling super. */
@Mixin({ServerPlayer.class, EndCrystal.class})
public abstract class MonsterCombatDamageMixin {
    @Inject(method="hurtServer", at=@At("HEAD"), cancellable=true)
    private void harm(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (P2CombatRuntime.rejectDamage((Entity)(Object)this, source)) ci.setReturnValue(false);
    }
}
