package dev.hardrush.monster.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityType.class)
public interface CompanionSpawnAccessor {
    @Invoker("getYOffset")
    static double hardrush$spawnYOffset(LevelReader level, BlockPos pos, boolean movedUp, AABB box) {
        throw new AssertionError("Mixin invoker");
    }
}
