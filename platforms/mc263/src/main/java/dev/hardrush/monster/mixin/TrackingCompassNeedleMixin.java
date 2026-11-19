package dev.hardrush.monster.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla re-sends the real world spawn with the level's other info (login, respawn, dimension change, and the
 * owner-skin refresh that reuses it). A tracking compass's needle would fall back to spawn until its next update,
 * so it is pointed at the quarry again right behind that packet.
 */
@Mixin(PlayerList.class)
abstract class TrackingCompassNeedleMixin {
    @Inject(method = "sendLevelInfo", at = @At("TAIL"))
    private void hardrush$repointNeedle(ServerPlayer player, ServerLevel level, CallbackInfo ci) {
        dev.hardrush.hunt.HuntRetracks.levelInfoSent(player);
    }
}
