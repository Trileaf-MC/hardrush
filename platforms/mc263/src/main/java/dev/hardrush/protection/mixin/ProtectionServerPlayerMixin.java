package dev.hardrush.protection.mixin;

import dev.hardrush.lobby.RevivalTotems;
import dev.hardrush.protection.ProtectionRuntime;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ProtectionServerPlayerMixin {
    @Inject(method="hurtServer", at=@At("HEAD"), cancellable=true)
    private void hurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (ProtectionRuntime.rejectDamage((ServerPlayer)(Object)this, source)) ci.setReturnValue(false);
    }
    @Inject(method="drop(Z)V", at=@At("HEAD"), cancellable=true)
    private void drop(boolean wholeStack, CallbackInfo ci) {
        var player = (ServerPlayer)(Object)this;
        if (ProtectionRuntime.protectedEntity(player) && !RevivalTotems.is(player.getInventory().getSelectedItem())) ci.cancel();
    }
    /** Every throw path (key, inventory screen, closing menus) reaches here after removing the stack. */
    @Inject(method="drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at=@At("HEAD"), cancellable=true)
    private void dropRevivalTotem(ItemStack stack, boolean thrown, Prediction prediction, CallbackInfoReturnable<ItemEntity> ci) {
        if (!RevivalTotems.is(stack)) return;
        RevivalTotems.thrown((ServerPlayer)(Object)this, stack.copy());
        ci.setReturnValue(null);
    }
    @Inject(method="teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at=@At("HEAD"), cancellable=true)
    private void portal(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> ci) {
        var player = (ServerPlayer)(Object)this;
        var target = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(player), PositionMoveRotation.of(transition), transition.relatives()).position();
        if (ProtectionRuntime.rejectMove(player, transition.newLevel(), target)) {
            ProtectionRuntime.correct(player); ci.setReturnValue(null);
        }
    }
    @Inject(method="teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z", at=@At("HEAD"), cancellable=true)
    private void teleport(ServerLevel level, double x, double y, double z, Set<Relative> relatives, float yaw, float pitch,
            boolean camera, CallbackInfoReturnable<Boolean> ci) {
        var player = (ServerPlayer)(Object)this;
        var target = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(player),
                new PositionMoveRotation(new Vec3(x,y,z), Vec3.ZERO, yaw, pitch), relatives).position();
        if (ProtectionRuntime.rejectMove(player, level, target)) {
            ProtectionRuntime.correct(player); ci.setReturnValue(false);
        }
    }
    @Inject(method="teleportTo(DDD)V", at=@At("HEAD"), cancellable=true)
    private void sameLevelTeleport(double x, double y, double z, CallbackInfo ci) {
        var player = (ServerPlayer)(Object)this;
        if (ProtectionRuntime.rejectMove(player, player.level(), new Vec3(x,y,z))) {
            ProtectionRuntime.correct(player); ci.cancel();
        }
    }
}
