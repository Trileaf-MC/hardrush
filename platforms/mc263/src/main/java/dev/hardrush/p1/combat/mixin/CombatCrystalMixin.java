package dev.hardrush.p1.combat.mixin;

import dev.hardrush.p1.combat.P1CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EndCrystal.class)
public abstract class CombatCrystalMixin {
    @Inject(method="hurtServer", at=@At("HEAD"), cancellable=true)
    private void frozen(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (P1CombatRuntime.rejectDamage(level)) ci.setReturnValue(false);
    }
    @Inject(method="hurtServer", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/boss/enderdragon/EndCrystal;remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void triggered(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        P1CombatRuntime.crystalTriggered(level, (EndCrystal)(Object)this, source);
    }
}
