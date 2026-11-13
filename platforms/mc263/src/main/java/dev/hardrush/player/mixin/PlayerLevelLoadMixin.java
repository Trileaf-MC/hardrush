package dev.hardrush.player.mixin;

import dev.hardrush.player.ClientLevelLoad;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks the native client's load acknowledgement per level, not only per login. See {@link ClientLevelLoad}. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerLevelLoadMixin implements ClientLevelLoad {
    @Unique private boolean hardrush$levelLoading;

    @Shadow public abstract boolean hasClientLoaded();

    // TAIL is only reached on the server thread: off-thread calls throw inside vanilla's PacketUtils rescheduling.
    @Inject(method = "handleAcceptPlayerLoad", at = @At("TAIL"))
    private void hardrush$acceptLevelLoad(ServerboundPlayerLoadedPacket packet, CallbackInfo ci) {
        // The packet carries no level id: one sent for the previous level just before the respawn packet arrived
        // would also clear this. Vanilla's own login gate shares that inherent window.
        hardrush$levelLoading = false;
    }

    @Override public boolean hardrush$levelLoaded() { return !hardrush$levelLoading && hasClientLoaded(); }
    @Override public void hardrush$levelChanged() { hardrush$levelLoading = true; }
}
