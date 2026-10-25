package net.minecraft.world.entity;

import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.animal.Animal;

public abstract class TamableAnimal extends Animal implements OwnableEntity {
   public void setTame(final boolean isTame, final boolean includeSideEffects) {
      byte current = this.entityData.get(DATA_FLAGS_ID);
      if (isTame) {
         this.entityData.set(DATA_FLAGS_ID, (byte)(current | 4));
      } else {
         this.entityData.set(DATA_FLAGS_ID, (byte)(current & -5));
      }

      if (includeSideEffects) {
         this.applyTamingSideEffects();
      }
   }
}
