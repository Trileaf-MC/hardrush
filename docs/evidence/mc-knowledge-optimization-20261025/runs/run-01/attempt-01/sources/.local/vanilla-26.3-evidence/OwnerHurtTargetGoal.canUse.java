public class OwnerHurtTargetGoal extends TargetGoal {
   @Override
   public boolean canUse() {
      if (this.tameAnimal.isTame() && !this.tameAnimal.isOrderedToSit()) {
         LivingEntity owner = this.tameAnimal.getOwner();
         if (owner == null) {
            return false;
         }

         this.ownerLastHurt = owner.getLastHurtMob();
         int ts = owner.getLastHurtMobTimestamp();
         return ts != this.timestamp
            && this.canAttack(this.ownerLastHurt, TargetingConditions.DEFAULT)
            && this.tameAnimal.wantsToAttack(this.ownerLastHurt, owner);
      } else {
         return false;
      }
   }
}
