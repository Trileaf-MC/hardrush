package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ItemEntity.class, ExperienceOrb.class})
public abstract class ProtectionPickupMixin {
    @Inject(method="playerTouch", at=@At("HEAD"), cancellable=true)
    private void touch(Player player, CallbackInfo ci) {
        if (ProtectionRuntime.protectedEntity(player) || ProtectionRuntime.protectedEntity((net.minecraft.world.entity.Entity)(Object)this)) ci.cancel();
    }
}
