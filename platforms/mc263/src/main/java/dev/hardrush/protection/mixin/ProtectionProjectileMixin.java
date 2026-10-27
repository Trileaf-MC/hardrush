package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class ProtectionProjectileMixin {
    @Inject(method="hitTargetOrDeflectSelf", at=@At("HEAD"), cancellable=true)
    private void hit(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> ci) {
        var projectile = (Projectile)(Object)this;
        if (ProtectionRuntime.rejectProjectilePath(projectile, hit.getLocation())) {
            projectile.discard(); ci.setReturnValue(ProjectileDeflection.NONE);
        }
    }
    @Inject(method="setOwner(Lnet/minecraft/world/entity/Entity;)V", at=@At("TAIL"))
    private void launch(Entity owner, CallbackInfo ci) {
        ProtectionRuntime.projectileCreated((Projectile)(Object)this, owner);
    }
}
