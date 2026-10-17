package dev.hardrush.p0.world.mixin;

import dev.hardrush.p1.world.IndependentWorldEnvironment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The constructor otherwise takes the lobby seed for structure checks and dragon gateways. */
@Mixin(ServerLevel.class)
public abstract class RuntimeLevelSeedMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/WorldOptions;seed()J"))
    private long hardrush$roundSeed(WorldOptions options) {
        if ((Object) this instanceof IndependentWorldEnvironment level) return level.getSeed();
        return options.seed();
    }
}
