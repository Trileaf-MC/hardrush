package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class ProtectionPlayerMixin {
    @Inject(method="attack", at=@At("HEAD"), cancellable=true)
    private void attack(Entity target, CallbackInfo ci) {
        if (ProtectionRuntime.protectedEntity((Entity)(Object)this) || ProtectionRuntime.protectedEntity(target)) ci.cancel();
    }
    /** A spear's jab and charge skip {@link #attack}; the same gate also stops their damage-free knockback and dismount. */
    @Inject(method="stabAttack", at=@At("HEAD"), cancellable=true)
    private void stab(EquipmentSlot slot, Entity target, float damage, boolean dealsDamage, boolean knockback, boolean dismounts,
                      CallbackInfoReturnable<Boolean> ci) {
        if (ProtectionRuntime.protectedEntity((Entity)(Object)this) || ProtectionRuntime.protectedEntity(target)) ci.setReturnValue(false);
    }
}
