package dev.hardrush.player.mixin;

import dev.hardrush.player.PlayerDisconnects;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server-thread leave point; see {@link PlayerDisconnects} for why Fabric's DISCONNECT event is not used. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerDisconnectMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void hardrush$disconnected(DisconnectionDetails details, CallbackInfo ci) {
        PlayerDisconnects.fire(player);
    }
}
