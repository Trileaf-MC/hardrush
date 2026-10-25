package net.minecraft.world.entity.ai.goal;

import net.minecraft.world.entity.LivingEntity;

public class SitWhenOrderedToGoal extends Goal {
   @Override
   public boolean canUse() {
      boolean orderedToSit = this.mob.isOrderedToSit();
      if (!orderedToSit && !this.mob.isTame()) {
         return false;
      } else if (this.mob.isInWater()) {
         return false;
      } else if (!this.mob.onGround()) {
         return false;
      } else {
         LivingEntity owner = this.mob.getOwner();
         if (owner == null || owner.level() != this.mob.level()) {
            return true;
         } else {
            return this.mob.distanceToSqr(owner) < 144.0 && owner.getLastHurtByMob() != null ? false : orderedToSit;
         }
      }
   }
}
