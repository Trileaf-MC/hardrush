package net.minecraft.world.entity.ai.goal;

import net.minecraft.world.entity.LivingEntity;

public class MeleeAttackGoal extends Goal {
   @Override
   public boolean canUse() {
      long time = this.mob.level().getGameTime();
      if (time - this.lastCanUseCheck < 20L) {
         return false;
      }

      this.lastCanUseCheck = time;
      LivingEntity target = this.mob.getTarget();
      if (target == null) {
         return false;
      }

      if (!target.isAlive()) {
         return false;
      }

      this.path = this.mob.getNavigation().createPath(target, 0);
      return this.path != null ? true : this.mob.isWithinMeleeAttackRange(target);
   }
}
