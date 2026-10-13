package dev.hardrush.p0.disguise.mixin;

import dev.hardrush.p0.disguise.DisguiseProbe;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class DisguiseConnectionMixin {
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
