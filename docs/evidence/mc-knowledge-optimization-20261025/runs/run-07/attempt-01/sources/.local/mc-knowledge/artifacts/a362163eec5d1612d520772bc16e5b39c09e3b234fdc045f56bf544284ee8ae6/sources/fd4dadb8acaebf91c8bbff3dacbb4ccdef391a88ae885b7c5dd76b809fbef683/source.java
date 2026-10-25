package net.minecraft.world.entity.ai.goal.target;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.scores.Team;

public abstract class TargetGoal extends Goal {
   @Override
   public boolean canContinueToUse() {
      LivingEntity target = this.mob.getTarget();
      if (target == null) {
         target = this.targetMob;
      }

      if (target == null) {
         return false;
      }

      if (!this.mob.canAttack(target)) {
         return false;
      }

      Team mobTeam = this.mob.getTeam();
      Team targetTeam = target.getTeam();
      if (mobTeam != null && targetTeam == mobTeam) {
         return false;
      }

      double within = this.getFollowDistance();
      if (this.mob.distanceToSqr(target) > within * within) {
         return false;
      }

      if (this.mustSee) {
         if (this.mob.getSensing().hasLineOfSight(target)) {
            this.unseenTicks = 0;
         } else if (++this.unseenTicks > reducedTickDelay(this.unseenMemoryTicks)) {
            return false;
         }
      }

      this.mob.setTarget(target);
      return true;
   }
}
