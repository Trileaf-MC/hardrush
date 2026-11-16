package dev.hardrush.gametest.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Test artifact only: {@code canUse} picks the entity to flee before it rolls a random escape position,
 * so tests read that choice instead of depending on the roll landing on the arena floor.
 */
@Mixin(AvoidEntityGoal.class)
public interface AvoidEntityGoalAccessor {
    @Accessor("toAvoid") LivingEntity hardrush$toAvoid();
}
