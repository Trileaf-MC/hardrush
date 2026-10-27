package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonStructureResolver.class)
public abstract class ProtectionPistonMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private Direction pushDirection;
    @Inject(method="resolve", at=@At("RETURN"), cancellable=true)
    private void resolve(CallbackInfoReturnable<Boolean> ci) {
        if (!ci.getReturnValue() || !(level instanceof ServerLevel serverLevel)) return;
        var resolver = (PistonStructureResolver)(Object)this;
        if (resolver.getToDestroy().stream().anyMatch(pos -> ProtectionRuntime.protectedBlock(serverLevel, pos))
                || resolver.getToPush().stream().anyMatch(pos -> ProtectionRuntime.protectedBlock(serverLevel, pos)
                        || ProtectionRuntime.protectedBlock(serverLevel, pos.relative(pushDirection)))) ci.setReturnValue(false);
    }
}
