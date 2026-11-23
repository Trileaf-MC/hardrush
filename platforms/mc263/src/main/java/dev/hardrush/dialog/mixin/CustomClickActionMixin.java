package dev.hardrush.dialog.mixin;

import dev.hardrush.dialog.DialogClicks;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla drops who clicked a dialog button; HardRush's own actions are answered with the player in hand. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class CustomClickActionMixin {
    // After the thread hop, so the handler runs on the server thread.
    @Inject(method = "handleCustomClickAction", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"))
    private void hardrush$route(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && DialogClicks.handle(game.player, packet.id(), packet.payload())) ci.cancel();
    }
}
