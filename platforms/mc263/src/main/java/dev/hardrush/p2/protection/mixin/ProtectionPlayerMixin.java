package dev.hardrush.p2.protection.mixin;

import dev.hardrush.p2.protection.P2ProtectionRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class ProtectionPlayerMixin {
    @Inject(method="attack", at=@At("HEAD"), cancellable=true)
    private void attack(Entity target, CallbackInfo ci) {
        if (P2ProtectionRuntime.protectedEntity((Entity)(Object)this) || P2ProtectionRuntime.protectedEntity(target)) ci.cancel();
    }
}
