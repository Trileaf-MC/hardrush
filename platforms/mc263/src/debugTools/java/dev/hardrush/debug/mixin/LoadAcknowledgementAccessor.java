package dev.hardrush.debug.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only verification that a real vanilla client acknowledged the cosmetic teleport. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface LoadAcknowledgementAccessor {
    @Accessor("awaitingPositionFromClient") Vec3 hardrush$awaitingPosition();
}
