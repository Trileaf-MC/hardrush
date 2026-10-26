package dev.hardrush.p0.monster.mixin;

import dev.hardrush.p0.monster.MonsterCreeper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class MonsterCreeperUseMixin {
    @Inject(method = "completeUsingItem", at = @At("HEAD"), cancellable = true)
    private void neverConsumeIgniter(CallbackInfo callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterCreeper.isUsingIgniter(player)) {
            MonsterCreeper.release(player);
            callback.cancel();
        }
    }
}
