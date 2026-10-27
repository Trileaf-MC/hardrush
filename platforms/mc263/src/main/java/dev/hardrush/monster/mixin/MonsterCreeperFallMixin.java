package dev.hardrush.monster.mixin;

import dev.hardrush.monster.MonsterCreeper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class MonsterCreeperFallMixin {
    @Inject(method = "causeFallDamage", at = @At("RETURN"))
    private void creeperFall(double distance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player) MonsterCreeper.onFall(player, distance);
    }
}
