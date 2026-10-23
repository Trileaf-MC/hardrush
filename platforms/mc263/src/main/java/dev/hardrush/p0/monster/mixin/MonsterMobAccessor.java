package dev.hardrush.p0.monster.mixin;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Mob.class)
public interface MonsterMobAccessor {
    @Accessor("targetSelector") GoalSelector hardrush$targetSelector();
    @Accessor("goalSelector") GoalSelector hardrush$goalSelector();
}
