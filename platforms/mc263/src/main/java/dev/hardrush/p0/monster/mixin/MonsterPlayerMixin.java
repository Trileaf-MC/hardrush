package dev.hardrush.p0.monster.mixin;

import dev.hardrush.p0.monster.MonsterRoles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class MonsterPlayerMixin {
    @Inject(method="tryToStartFallFlying", at=@At("HEAD"), cancellable=true)
    private void noPlayerGliding(CallbackInfoReturnable<Boolean> callback) {
        if (MonsterRoles.isMonster((Player)(Object)this)) callback.setReturnValue(false);
    }
    @Inject(method="hurtArmor", at=@At("HEAD"), cancellable=true)
    private void nativeArmorDurability(DamageSource source, float amount, CallbackInfo callback) {
        if (MonsterRoles.isMonster((Player)(Object)this)) callback.cancel();
    }
    @Inject(method="getHurtSound", at=@At("HEAD"), cancellable=true)
    private void nativeHurtSound(DamageSource source, CallbackInfoReturnable<SoundEvent> callback) {
        if ((Object)this instanceof ServerPlayer player) {
            var sound=MonsterRoles.hurtSound(player);
            if (sound!=null) callback.setReturnValue(sound);
        }
    }
    @Inject(method="getDeathSound", at=@At("HEAD"), cancellable=true)
    private void nativeDeathSound(CallbackInfoReturnable<SoundEvent> callback) {
        if ((Object)this instanceof ServerPlayer player) {
            var sound=MonsterRoles.deathSound(player);
            if (sound!=null) callback.setReturnValue(sound);
        }
    }
}
