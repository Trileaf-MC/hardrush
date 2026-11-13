package dev.hardrush.player.mixin;

import dev.hardrush.player.ClientLevelLoad;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every path that makes the native client open a level-loading screen (dimension teleport, respawn) sends this packet. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PlayerLevelChangeMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"))
    private void hardrush$levelChanged(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if (packet instanceof ClientboundRespawnPacket && (Object) this instanceof ClientLevelLoad load) load.hardrush$levelChanged();
    }
}
