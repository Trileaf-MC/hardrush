package net.minecraft.world.entity.ai.targeting;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

public class TargetingConditions {
   public boolean test(final ServerLevel level, final @Nullable LivingEntity targeter, final LivingEntity target) {
      if (targeter == target) {
         return false;
      }

      if (!target.canBeSeenByAnyone()) {
         return false;
      }

      if (this.selector != null && !this.selector.test(target, level)) {
         return false;
      }

      if (targeter == null) {
         if (this.isCombat && (!target.canBeSeenAsEnemy() || level.getDifficulty() == Difficulty.PEACEFUL)) {
            return false;
         }
      } else {
         if (this.isCombat && (!targeter.canAttack(target) || targeter.isAlliedTo(target))) {
            return false;
         }

         if (this.range > 0.0) {
            double modifier = this.testInvisible ? target.getVisibilityPercent(level, targeter) : 1.0;
            double visibilityDistance = Math.max(this.range * modifier, 2.0);
            double distanceToSqr = targeter.distanceToSqr(target.getX(), target.getY(), target.getZ());
            if (distanceToSqr > visibilityDistance * visibilityDistance) {
               return false;
            }
         }

         if (this.checkLineOfSight && targeter instanceof Mob mob && !mob.getSensing().hasLineOfSight(target)) {
            return false;
         }
      }

      return true;
   }
}
