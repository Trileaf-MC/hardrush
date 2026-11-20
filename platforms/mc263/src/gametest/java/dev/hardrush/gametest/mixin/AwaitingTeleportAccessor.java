package dev.hardrush.gametest.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Test artifact only: a connection a test client was attached over, handed back, must stop waiting for a teleport the
 * attached connection has since overtaken. No real server swaps connections, so no packet exists to say so.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface AwaitingTeleportAccessor {
    @Accessor("awaitingPositionFromClient") void hardrush$awaitingPositionFromClient(Vec3 position);
}
