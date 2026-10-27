package dev.hardrush.monster.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface MonsterEntityAccessor {
    @Invoker("updateFluidInteraction") boolean hardrush$updateFluidInteraction();
    @Accessor("wasInPowderSnow") boolean hardrush$wasInPowderSnow();
    @Accessor("wasInPowderSnow") void hardrush$setWasInPowderSnow(boolean value);
}
