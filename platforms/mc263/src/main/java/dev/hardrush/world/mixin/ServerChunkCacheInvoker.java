package dev.hardrush.world.mixin;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's {@code save} promotes pending tickets first; {@link dev.hardrush.world.CheckpointFlush#waiting} does the same. */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheInvoker {
    @Invoker("runDistanceManagerUpdates") boolean hardrush$runDistanceManagerUpdates();
}
