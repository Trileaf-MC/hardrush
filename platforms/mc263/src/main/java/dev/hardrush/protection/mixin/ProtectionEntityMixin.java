package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
import dev.hardrush.protection.ProtectionOriginHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class ProtectionEntityMixin implements ProtectionOriginHolder {
    @Unique private boolean hardrush$protectedOrigin;
    @Override public boolean hardrush$protectedOrigin() { return hardrush$protectedOrigin; }
    @Override public void hardrush$protectedOrigin(boolean value) { hardrush$protectedOrigin |= value; }

    @Inject(method="saveWithoutId", at=@At("TAIL"))
    private void save(ValueOutput output, CallbackInfo ci) {
        if (hardrush$protectedOrigin) output.putBoolean("hardrush_protected_origin", true);
    }
    @Inject(method="load", at=@At("TAIL"))
    private void load(ValueInput input, CallbackInfo ci) {
        hardrush$protectedOrigin = input.getBooleanOr("hardrush_protected_origin", false);
    }
    @Inject(method="hurtOrSimulate", at=@At("HEAD"), cancellable=true)
    private void harm(DamageSource source, float damage, CallbackInfoReturnable<Boolean> ci) {
        if (ProtectionRuntime.rejectDamage((Entity)(Object)this, source)) ci.setReturnValue(false);
    }
    @Inject(method="setPos(DDD)V", at=@At("HEAD"), cancellable=true)
    private void position(double x, double y, double z, CallbackInfo ci) {
        var entity = (Entity)(Object)this;
        if (entity instanceof Projectile projectile && entity.tickCount > 0
                && ProtectionRuntime.rejectProjectilePath(projectile, new Vec3(x,y,z))) {
            entity.discard(); ci.cancel(); return;
        }
        if (ProtectionRuntime.rejectPosition(entity, new Vec3(x, y, z))) {
            entity.setDeltaMovement(Vec3.ZERO); entity.resetFallDistance(); ci.cancel();
        }
    }
    @Inject(method="setPos(DDD)V", at=@At("TAIL"))
    private void moved(double x, double y, double z, CallbackInfo ci) {
        ProtectionRuntime.positionAccepted((Entity)(Object)this);
    }
    @Inject(method="teleport", at=@At("HEAD"), cancellable=true)
    private void teleport(net.minecraft.world.level.portal.TeleportTransition transition, CallbackInfoReturnable<Entity> ci) {
        var entity = (Entity)(Object)this;
        var target = net.minecraft.world.entity.PositionMoveRotation.calculateAbsolute(
                net.minecraft.world.entity.PositionMoveRotation.of(entity),
                net.minecraft.world.entity.PositionMoveRotation.of(transition), transition.relatives()).position();
        if (ProtectionRuntime.rejectMove(entity, transition.newLevel(), target)) {
            entity.setDeltaMovement(Vec3.ZERO); entity.resetFallDistance(); ci.setReturnValue(null);
        }
    }
    @Inject(method="setRemainingFireTicks", at=@At("HEAD"), cancellable=true)
    private void fire(int ticks, CallbackInfo ci) {
        if (ticks > 0 && ProtectionRuntime.protectedEntity((Entity)(Object)this)) ci.cancel();
    }
    @Inject(method="ignoreExplosion", at=@At("HEAD"), cancellable=true)
    private void explosion(net.minecraft.world.level.Explosion explosion, CallbackInfoReturnable<Boolean> ci) {
        if (ProtectionRuntime.protectedEntity((Entity)(Object)this)) ci.setReturnValue(true);
    }
    @Inject(method="push(Lnet/minecraft/world/entity/Entity;)V", at=@At("HEAD"), cancellable=true)
    private void collision(Entity other, CallbackInfo ci) {
        if (ProtectionRuntime.protectedEntity((Entity)(Object)this) || ProtectionRuntime.protectedEntity(other)) ci.cancel();
    }
    @Inject(method="push(DDD)V", at=@At("HEAD"), cancellable=true)
    private void push(double x, double y, double z, CallbackInfo ci) {
        if (ProtectionRuntime.protectedEntity((Entity)(Object)this)) ci.cancel();
    }
}
