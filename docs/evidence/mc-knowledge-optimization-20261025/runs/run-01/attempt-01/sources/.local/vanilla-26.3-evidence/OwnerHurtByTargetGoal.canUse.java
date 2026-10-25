public class OwnerHurtByTargetGoal extends TargetGoal {
   @Override
   public boolean canUse() {
      if (this.tameAnimal.isTame() && !this.tameAnimal.isOrderedToSit()) {
         LivingEntity owner = this.tameAnimal.getOwner();
         if (owner == null) {
            return false;
         } else {
            DamageSource lastDamageSource = owner.getLastDamageSource(100);
            if (lastDamageSource != null && !lastDamageSource.is(DamageTypeTags.NO_WOLF_RETALIATION)) {
               this.ownerLastHurtBy = owner.getLastHurtByMob();
               int ts = owner.getLastHurtByMobTimestamp();
               return ts != this.timestamp
                  && this.canAttack(this.ownerLastHurtBy, TargetingConditions.DEFAULT)
                  && this.tameAnimal.wantsToAttack(this.ownerLastHurtBy, owner);
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }
}
