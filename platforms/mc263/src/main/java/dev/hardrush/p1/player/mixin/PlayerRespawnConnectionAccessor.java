package dev.hardrush.p1.player.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Completes the native network handler's caller-owned respawn bookkeeping. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface PlayerRespawnConnectionAccessor {
    @Invoker("restartClientLoadTimerAfterRespawn")
    void hardrush$restartClientLoadTimerAfterRespawn();
}
