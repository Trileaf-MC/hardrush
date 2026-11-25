package dev.hardrush.world.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setTransformation") void hardrush$setTransformation(Transformation transformation);
    @Invoker("setBillboardConstraints") void hardrush$setBillboardConstraints(Display.BillboardConstraints billboard);
}
