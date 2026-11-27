package dev.hardrush.monster.mixin;

import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractSkeleton.class)
public interface MonsterSkeletonAccessor {
    @Invoker("getArrow") AbstractArrow hardrush$getArrow(ItemStack ammunition, float power, ItemStack weapon);
    /** Ticks between native shots off hard difficulty; bogged and parched shoot slower. */
    @Invoker("getAttackInterval") int hardrush$getAttackInterval();
    @Invoker("getHardAttackInterval") int hardrush$getHardAttackInterval();
}
