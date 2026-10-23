package dev.hardrush.p0.monster.mixin.ai;

import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HurtByTargetGoal.class)
public interface SpeciesRetaliationAccessor {
    @Accessor("toIgnoreDamage") Class<?>[] hardrush$ignoredAttackerClasses();
}
