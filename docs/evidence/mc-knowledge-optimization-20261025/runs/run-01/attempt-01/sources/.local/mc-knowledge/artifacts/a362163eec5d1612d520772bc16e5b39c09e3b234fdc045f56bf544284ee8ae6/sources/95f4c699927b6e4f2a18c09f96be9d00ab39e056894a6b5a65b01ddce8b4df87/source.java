package net.minecraft.world.entity.ai.goal.target;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.jspecify.annotations.Nullable;

public abstract class TargetGoal extends Goal {
   protected boolean canAttack(final @Nullable LivingEntity target, final TargetingConditions targetConditions) {
      if (target == null) {
         return false;
      }

      if (!targetConditions.test(getServerLevel(this.mob), this.mob, target)) {
         return false;
      }

      if (!this.mob.isWithinHome(target.blockPosition())) {
         return false;
      }

      if (this.mustReach) {
         if (--this.reachCacheTime <= 0) {
            this.reachCache = 0;
         }

         if (this.reachCache == 0) {
            this.reachCache = this.canReach(target) ? 1 : 2;
         }

         if (this.reachCache == 2) {
            return false;
         }
      }

      return true;
   }
}
