package dev.hardrush.player.mixin;

import dev.hardrush.player.ClientLevelLoad;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the native client's load acknowledgement per level, not only per login. See {@link ClientLevelLoad}.
 *
 * <p>The loaded packet names no level, so one the client sent for the previous level can still be in flight when the
 * respawn packet goes out. Every respawn path (vanilla dimension teleport, {@code PlayerList.respawn}, the owner-skin
 * refresh) follows the respawn packet with a position packet, and the client acknowledges that teleport while handling
 * it, after the respawn. The connection is ordered, so a loaded packet counts only once that acknowledgement arrived.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerLevelLoadMixin implements ClientLevelLoad {
    @Unique private static final int NO_TELEPORT = Integer.MIN_VALUE;
    @Unique private boolean hardrush$levelLoading;
    @Unique private int hardrush$arrivalTeleport = NO_TELEPORT;
    @Unique private boolean hardrush$arrived;

    @Shadow public abstract boolean hasClientLoaded();

    // RETURN is only reached on the server thread: off-thread calls throw inside vanilla's PacketUtils rescheduling.
    @Inject(method = "handleAcceptTeleportPacket", at = @At("RETURN"))
    private void hardrush$acceptArrival(ServerboundAcceptTeleportationPacket packet, CallbackInfo ci) {
        if (hardrush$levelLoading && hardrush$arrivalTeleport != NO_TELEPORT && packet.id() == hardrush$arrivalTeleport) hardrush$arrived = true;
    }

    @Inject(method = "handleAcceptPlayerLoad", at = @At("RETURN"))
    private void hardrush$acceptLevelLoad(ServerboundPlayerLoadedPacket packet, CallbackInfo ci) {
        // Before the arrival acknowledgement this packet still belongs to the previous level.
        if (hardrush$arrived) hardrush$levelLoading = false;
    }

    @Override public boolean hardrush$levelLoaded() { return !hardrush$levelLoading && hasClientLoaded(); }

    @Override public void hardrush$levelChanged() {
        hardrush$levelLoading = true;
        hardrush$arrivalTeleport = NO_TELEPORT;
        hardrush$arrived = false;
    }

    @Override public void hardrush$teleportSent(int id) {
        if (hardrush$levelLoading && hardrush$arrivalTeleport == NO_TELEPORT) hardrush$arrivalTeleport = id;
    }
}
