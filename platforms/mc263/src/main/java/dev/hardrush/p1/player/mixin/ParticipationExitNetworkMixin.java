package dev.hardrush.p1.player.mixin;

import dev.hardrush.p1.participation.P1ParticipationControl;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The lower merge priority places this HEAD injection before the default-priority inventory locks. */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 900)
public abstract class ParticipationExitNetworkMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void hardrush$withdrawalShortcut(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        // Off-thread packets continue to vanilla scheduling and are consumed on the server-thread replay.
        if (player.level().getServer().isSameThread()
                && packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                && P1ParticipationControl.withdrawalShortcut(player)) ci.cancel();
    }
}
