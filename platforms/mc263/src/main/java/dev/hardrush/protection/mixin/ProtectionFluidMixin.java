package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
public abstract class ProtectionFluidMixin {
    @Inject(method="spreadTo", at=@At("HEAD"), cancellable=true)
    private void flow(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState fluid, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel && (ProtectionRuntime.protectedBlock(serverLevel, pos)
                || ProtectionRuntime.protectedBlock(serverLevel, pos.relative(direction.getOpposite())))) ci.cancel();
    }
}
