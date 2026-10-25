public class MeleeAttackGoal extends Goal {
   protected boolean canPerformAttack(final LivingEntity target) {
      return this.isTimeToAttack() && this.mob.isWithinMeleeAttackRange(target) && this.mob.getSensing().hasLineOfSight(target);
   }
}
