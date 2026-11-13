package dev.hardrush.player.mixin;

import dev.hardrush.player.ClientLevelLoad;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes what actually reaches the wire, in order: the respawn packet that opens the client's level-loading screen
 * (dimension teleport, respawn, owner-skin refresh) and the position packets after it. The cosmetic batch cancels the
 * single sends and delivers them as one bundle, so bundles are walked in order too.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PlayerLevelChangeMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V"))
    private void hardrush$observeLevelChange(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if ((Object) this instanceof ClientLevelLoad load) hardrush$observe(load, packet);
    }

    @Unique
    private static void hardrush$observe(ClientLevelLoad load, Packet<?> packet) {
        switch (packet) {
            case ClientboundRespawnPacket ignored -> load.hardrush$levelChanged();
            case ClientboundPlayerPositionPacket position -> load.hardrush$teleportSent(position.id());
            case BundlePacket<?> bundle -> { for (var child : bundle.subPackets()) hardrush$observe(load, child); }
            default -> { }
        }
    }
}
