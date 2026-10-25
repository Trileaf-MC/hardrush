package net.minecraft.world.entity.ai.goal;

import net.minecraft.world.entity.LivingEntity;

public class MeleeAttackGoal extends Goal {
   protected boolean canPerformAttack(final LivingEntity target) {
      return this.isTimeToAttack() && this.mob.isWithinMeleeAttackRange(target) && this.mob.getSensing().hasLineOfSight(target);
   }
}
