package dev.hardrush.death.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.hardrush.death.DeathNarration;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one death message {@code ServerPlayer.die} shows its victim and broadcasts ({@link DeathNarration}). The death is
 * settled at the head, before vanilla's {@code showDeathMessages} rule decides whether a message is built at all.
 */
@Mixin(ServerPlayer.class)
public abstract class DeathMessageMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void hardrush$dying(DamageSource source, CallbackInfo callback) {
        DeathNarration.dying((ServerPlayer) (Object) this, source);
    }

    @WrapOperation(method = "die", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/CombatTracker;getDeathMessage()Lnet/minecraft/network/chat/Component;"))
    private Component hardrush$narrate(CombatTracker tracker, Operation<Component> original,
            @Local(argsOnly = true) DamageSource source) {
        return DeathNarration.deathMessage((ServerPlayer) (Object) this, source, () -> original.call(tracker));
    }

    @Inject(method = "die", at = @At("RETURN"))
    private void hardrush$died(DamageSource source, CallbackInfo callback) {
        DeathNarration.died((ServerPlayer) (Object) this);
    }
}
