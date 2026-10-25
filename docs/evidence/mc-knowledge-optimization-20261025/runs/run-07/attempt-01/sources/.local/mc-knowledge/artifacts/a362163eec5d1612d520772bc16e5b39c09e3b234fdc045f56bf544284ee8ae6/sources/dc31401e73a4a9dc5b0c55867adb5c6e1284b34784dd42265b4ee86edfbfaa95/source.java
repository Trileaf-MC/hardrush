package net.minecraft.world.entity;

import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.animal.Animal;

public abstract class TamableAnimal extends Animal implements OwnableEntity {
   @Override
   public boolean canAttack(final LivingEntity target) {
      return this.isOwnedBy(target) ? false : super.canAttack(target);
   }
}
