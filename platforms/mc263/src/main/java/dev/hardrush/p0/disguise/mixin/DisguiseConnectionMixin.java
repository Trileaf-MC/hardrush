package dev.hardrush.p0.disguise.mixin;

import dev.hardrush.p0.disguise.DisguiseProbe;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import io.netty.channel.ChannelFutureListener;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class DisguiseConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void hardrush$captureCosmeticBatch(Packet<?> packet, ChannelFutureListener listener, CallbackInfo callback) {
        if ((Object) this instanceof ServerGamePacketListenerImpl handler
                && DisguiseProbe.captureCosmeticBatch(handler.player, packet)) callback.cancel();
    }
    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Packet<?> hardrush$rewriteDisguisePacket(Packet<?> packet) {
        if ((Object) this instanceof ServerGamePacketListenerImpl handler) {
            return DisguiseProbe.rewrite(handler.player, packet);
        }
        return packet;
    }
}
