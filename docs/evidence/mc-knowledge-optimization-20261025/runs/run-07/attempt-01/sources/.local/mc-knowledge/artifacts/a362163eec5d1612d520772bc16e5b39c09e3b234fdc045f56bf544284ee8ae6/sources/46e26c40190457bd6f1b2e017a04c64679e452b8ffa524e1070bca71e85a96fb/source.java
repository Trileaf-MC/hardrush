package net.minecraft.world.entity.ai.goal.target;

import net.minecraft.world.entity.LivingEntity;

public class NearestAttackableTargetGoal<T extends LivingEntity> extends TargetGoal {
   @Override
   public boolean canUse() {
      if (this.randomInterval > 0 && this.mob.getRandom().nextInt(this.randomInterval) != 0) {
         return false;
      }

      this.findTarget();
      return this.target != null;
   }
}
