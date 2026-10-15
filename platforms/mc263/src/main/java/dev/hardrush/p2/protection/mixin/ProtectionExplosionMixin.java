package dev.hardrush.p2.protection.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import java.util.List;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
public abstract class ProtectionExplosionMixin {
    @WrapMethod(method="interactWithBlocks")
    private void blocks(List<BlockPos> positions, Operation<Void> original) {
        var explosion = (ServerExplosion)(Object)this;
        original.call(new java.util.ArrayList<>(positions.stream().filter(pos -> !P2ProtectionRuntime.protectedBlock(explosion.level(), pos)).toList()));
    }
    @Inject(method="explode", at=@At("HEAD"), cancellable=true)
    private void explode(CallbackInfoReturnable<Integer> ci) {
        var explosion = (ServerExplosion)(Object)this;
        if (P2ProtectionRuntime.protectedAt(explosion.level(), explosion.center())) ci.setReturnValue(0);
    }
}
