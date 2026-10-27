package dev.hardrush.player.mixin;

import dev.hardrush.player.PlayerProfiles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class PlayerIsolationEntityMixin {
    @Inject(method = {"loadAndSpawnParentVehicle", "loadAndSpawnEnderPearls"}, at = @At("HEAD"), cancellable = true)
    private void hardrush$holdLateLoginEntities(ValueInput input, CallbackInfo ci) {
        if (PlayerProfiles.isLocked((ServerPlayer) (Object) this)) ci.cancel();
    }
    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdSimulation(CallbackInfo ci) {
        if (PlayerProfiles.simulationFrozen((ServerPlayer) (Object) this)) ci.cancel();
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdWorldTick(CallbackInfo ci) {
        var player = (ServerPlayer) (Object) this;
        if (PlayerProfiles.simulationFrozen(player)) {
            if (player.connection != null) player.connection.tickClientLoadTimeout();
            ci.cancel();
        }
    }
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdDamage(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (PlayerProfiles.isLocked((ServerPlayer) (Object) this)) ci.setReturnValue(false);
    }
}
