package dev.hardrush.world.mixin;

import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla spawns a player for a new viewer at {@link ServerEntity#getPositionBase()}, the spot its trackers were last
 * told, and only syncs a same-level teleport on the next update interval. {@code ChunkMap.tick} pairs viewers before
 * that update, so a teleported player first appears where it left and then slides to where it really is.
 *
 * <p>A hunter launched from a quarry's camera stood (as a hidden spectator) exactly on the quarry: the quarry saw the
 * monster flash inside its own body before it jumped to the landing. Settling the stale base first — one absolute
 * sync to the viewers who already see this player, as vanilla's own next update would send — makes every viewer,
 * old and new, see the real spot.</p>
 */
@Mixin(ServerEntity.class)
public abstract class PlayerTeleportPairingMixin {
    /** Farther than a player travels in one update interval, even gliding; only a teleport leaves the base this stale. */
    @Unique private static final double HARDRUSH$STALE_BASE_SQR = 4 * 4;

    @Shadow @Final private Entity entity;
    @Shadow @Final private ServerEntity.Synchronizer synchronizer;
    @Shadow @Final private VecDeltaCodec positionCodec;
    @Shadow @Final private InterpolationTracker interpolationTracker;
    @Shadow private int teleportDelay;

    @Inject(method = "sendPairingData", at = @At("HEAD"))
    private void hardrush$settleTeleportBeforePairing(ServerPlayer viewer, Consumer<Packet<ClientGamePacketListener>> sender,
                                                     CallbackInfo ci) {
        // Passengers follow their vehicle on every client; their base is never sent as a position.
        if (!(entity instanceof ServerPlayer) || entity.isPassenger()) return;
        var actual = entity.trackingPosition();
        if (positionCodec.getBase().distanceToSqr(actual) <= HARDRUSH$STALE_BASE_SQR) return;
        // The new viewer is already in seenBy; it learns the spot from its own spawn packet below.
        synchronizer.sendToTrackingPlayersFiltered(ClientboundEntityPositionSyncPacket.of(entity), player -> player != viewer);
        positionCodec.setBase(actual);
        interpolationTracker.clear();
        teleportDelay = 0;
    }
}
