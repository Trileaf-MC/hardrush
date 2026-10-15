package dev.hardrush.p1.player.mixin;

import dev.hardrush.p1.player.P1PlayerProfiles;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerLoginIsolationMixin {
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void hardrush$isolateBeforeLogin(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        P1PlayerProfiles.beforeLogin(player);
    }
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void hardrush$syncLobbyAfterLogin(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        P1PlayerProfiles.afterLogin(player);
    }
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void hardrush$preserveUnsafeOriginal(ServerPlayer player, CallbackInfo ci) {
        if (!P1PlayerProfiles.allowVanillaSave(player)) {
            // DISCONNECT removes the managed entry before vanilla save(). Isolated monster/lobby
            // NBT must stay quarantined, while cumulative statistics are independent of round profiles.
            player.getStats().save();
            ci.cancel();
        }
    }
}
