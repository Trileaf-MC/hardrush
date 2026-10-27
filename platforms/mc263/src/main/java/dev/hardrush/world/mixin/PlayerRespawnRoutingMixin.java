package dev.hardrush.world.mixin;

import dev.hardrush.world.RoundWorldRouting;
import dev.hardrush.player.PlayerProfiles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class PlayerRespawnRoutingMixin {
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
    private void hardrush$monsterLobby(boolean consumeSpawnBlock, TeleportTransition.PostTeleportTransition post,
                                      CallbackInfoReturnable<TeleportTransition> cir) {
        PlayerProfiles.monsterRespawnDestination((ServerPlayer) (Object) this).ifPresent(cir::setReturnValue);
    }
    @Redirect(method = "findRespawnPositionAndUseSpawnBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getRespawnConfig()Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;"))
    private ServerPlayer.RespawnConfig hardrush$ignoreForeignSpawn(ServerPlayer player) {
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        if (config != null && RoundWorldRouting.owns(player.level())) {
            var destination = player.level().getServer().getLevel(config.respawnData().dimension());
            if (destination == null || !RoundWorldRouting.sameRound(player.level(), destination)) return null;
        }
        // Reject a foreign bed/anchor before vanilla can consume its charge or touch its world.
        return config;
    }

    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
    private void hardrush$boundRespawn(boolean consumeSpawnBlock, TeleportTransition.PostTeleportTransition post,
                                      CallbackInfoReturnable<TeleportTransition> cir) {
        var player = (ServerPlayer) (Object) this;
        cir.setReturnValue(PlayerProfiles.monsterRespawnDestination(player)
                .orElseGet(() -> RoundWorldRouting.boundRespawn(player, cir.getReturnValue())));
    }
}
