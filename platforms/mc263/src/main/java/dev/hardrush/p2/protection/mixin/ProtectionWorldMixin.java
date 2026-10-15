package dev.hardrush.p2.protection.mixin;

import dev.hardrush.p2.protection.P2ProtectionRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Protects native fluid, explosion, fire and block-update paths as well as player edits. */
@Mixin(Level.class)
public abstract class ProtectionWorldMixin {
    @Inject(method="setBlock", at=@At("HEAD"), cancellable=true)
    private void set(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> ci) {
        if ((Object)this instanceof ServerLevel level && P2ProtectionRuntime.protectedBlock(level, pos)) ci.setReturnValue(false);
    }
    @Inject(method="destroyBlock", at=@At("HEAD"), cancellable=true)
    private void destroy(BlockPos pos, boolean drop, Entity entity, int recursion, CallbackInfoReturnable<Boolean> ci) {
        if ((Object)this instanceof ServerLevel level && P2ProtectionRuntime.protectedBlock(level, pos)) ci.setReturnValue(false);
    }
}
