package dev.hardrush.monster.mixin;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Creeper.class)
public interface NativeCreeperAccessor {
    @Accessor("maxSwell") int hardrush$maxSwell();
    @Accessor("explosionRadius") int hardrush$explosionRadius();
}
