package dev.hardrush.world.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.world.CheckpointFlush;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** See {@link CheckpointFlush}: the flushing save waits for writes, and also for POI writes vanilla leaves queued. */
@Mixin(ChunkMap.class)
public abstract class CheckpointChunkFlushMixin {
    @Shadow @Final private PoiManager poiManager;

    @WrapOperation(method = "saveAllChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;synchronize(Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> hardrush$checkpointWritesOnly(ChunkMap map, boolean flush, Operation<CompletableFuture<Void>> original) {
        if (!CheckpointFlush.active()) return original.call(map, flush);
        ((SectionStorageAccessor) poiManager).hardrush$regionStorage().synchronize(false).join();
        return original.call(map, false);
    }
}
