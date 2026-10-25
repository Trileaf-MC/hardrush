package net.minecraft.world.entity.ai.goal.target;

import net.minecraft.world.entity.LivingEntity;

public class NonTameRandomTargetGoal<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {
   @Override
   public boolean canUse() {
      return !this.tamableMob.isTame() && super.canUse();
   }
}
