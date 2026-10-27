package dev.hardrush.world.mixin;

import dev.hardrush.world.RoundWorldRouting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import dev.hardrush.celebration.CelebrationRuntime;

@Mixin(EndPortalBlock.class)
public abstract class EndPortalRoutingMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void hardrush$celebrationReturn(ServerLevel source, Entity entity, BlockPos entry,
            CallbackInfoReturnable<TeleportTransition> ci) {
        if (entity instanceof ServerPlayer player && CelebrationRuntime.returnThroughPortal(player)) ci.setReturnValue(null);
    }
    @Redirect(method = "getPortalDestination", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"))
    private ResourceKey<Level> hardrush$semanticDimension(ServerLevel level) {
        return RoundWorldRouting.semanticDimension(level);
    }

    @Redirect(method = "getPortalDestination", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel hardrush$roundDestination(MinecraftServer server, ResourceKey<Level> requested,
                                                  ServerLevel source, Entity entity, BlockPos entry) {
        return RoundWorldRouting.portalDestination(source, requested);
    }
}
