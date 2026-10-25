package net.minecraft.world.entity.ai.goal;

public class SitWhenOrderedToGoal extends Goal {
   @Override
   public boolean canContinueToUse() {
      return this.mob.isOrderedToSit();
   }
}
