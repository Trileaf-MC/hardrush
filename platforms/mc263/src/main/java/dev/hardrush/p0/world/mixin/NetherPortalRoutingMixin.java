package dev.hardrush.p0.world.mixin;

import dev.hardrush.p1.world.RoundWorldRouting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalRoutingMixin {
    @Redirect(method = "getPortalDestination", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"), require = 2)
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
