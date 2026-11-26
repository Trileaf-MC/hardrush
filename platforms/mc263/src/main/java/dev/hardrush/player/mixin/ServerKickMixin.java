package dev.hardrush.player.mixin;

import dev.hardrush.player.ServerKicked;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers the reason the server itself gave for closing a connection ({@code /kick}, a stop, a timeout...), so the
 * leave point can tell it from a client that just went away ({@link dev.hardrush.player.PlayerDisconnects#cause}).
 * Every server-side disconnect of a play connection goes through {@code disconnect(DisconnectionDetails)}; the
 * {@code Component} overload wraps it.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerKickMixin implements ServerKicked {
    // Vanilla kicks from the server thread; volatile in case a packet error kicks from the network thread.
    @Unique private volatile DisconnectionDetails hardrush$serverKick;

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void hardrush$remember(DisconnectionDetails details, CallbackInfo ci) {
        if (hardrush$serverKick == null) hardrush$serverKick = details;
    }

    @Override public DisconnectionDetails hardrush$serverKick() { return hardrush$serverKick; }
}
