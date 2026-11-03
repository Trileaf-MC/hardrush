package dev.hardrush.world.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.world.CheckpointFlush;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** See {@link CheckpointFlush}: entity writes are awaited, never forced to disk, during a checkpoint save. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class CheckpointEntityFlushMixin {
    @WrapOperation(method = "saveAll", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;flush(Z)V"))
    private void hardrush$checkpointWritesOnly(EntityPersistentStorage<?> storage, boolean flush, Operation<Void> original) {
        original.call(storage, flush && !CheckpointFlush.active());
    }
}
