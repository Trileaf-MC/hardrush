package dev.hardrush.world.mixin;

import dev.hardrush.world.RoundWorldRouting;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BaseFireBlock.class)
public abstract class FirePortalDimensionMixin {
    @Redirect(method = "inPortalDimension", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;dimension()Lnet/minecraft/resources/ResourceKey;"), require = 2)
    private static ResourceKey<Level> hardrush$semanticDimension(Level level) {
        // Both fire placement and portal creation use this check; retain vanilla rules outside owned rounds.
        return level instanceof ServerLevel serverLevel
                ? RoundWorldRouting.semanticDimension(serverLevel) : level.dimension();
    }
}
