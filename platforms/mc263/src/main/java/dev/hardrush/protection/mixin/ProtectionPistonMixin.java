package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
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
    @Inject(method="resolve", at=@At("RETURN"), cancellable=true)
    private void resolve(CallbackInfoReturnable<Boolean> ci) {
        if (ci.getReturnValue() && level instanceof ServerLevel serverLevel && ProtectionRuntime.protectedLevel(serverLevel)) ci.setReturnValue(false);
    }
}
