package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
public abstract class ProtectionExplosionMixin {
    @Inject(method="explode", at=@At("HEAD"), cancellable=true)
    private void explode(CallbackInfoReturnable<Integer> ci) {
        var explosion = (ServerExplosion)(Object)this;
        if (ProtectionRuntime.protectedLevel(explosion.level())) ci.setReturnValue(0);
    }
}
