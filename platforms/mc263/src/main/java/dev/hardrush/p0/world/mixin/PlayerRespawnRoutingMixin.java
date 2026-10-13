package dev.hardrush.p0.world.mixin;

import dev.hardrush.p0.world.WorldProbe;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class PlayerRespawnRoutingMixin {
    @Redirect(method = "findRespawnPositionAndUseSpawnBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getRespawnConfig()Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;"))
    private ServerPlayer.RespawnConfig hardrush$ignoreForeignSpawn(ServerPlayer player) {
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        if (config != null && WorldProbe.owns(player.level())) {
            var destination = player.level().getServer().getLevel(config.respawnData().dimension());
            if (destination == null || !WorldProbe.owns(destination)) return null;
        }
        // Reject a foreign bed/anchor before vanilla can consume its charge or touch its world.
        return config;
    }

    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
    private void hardrush$boundRespawn(boolean consumeSpawnBlock, TeleportTransition.PostTeleportTransition post,
                                      CallbackInfoReturnable<TeleportTransition> cir) {
        cir.setReturnValue(WorldProbe.boundRespawn((ServerPlayer) (Object) this, cir.getReturnValue()));
    }
}
