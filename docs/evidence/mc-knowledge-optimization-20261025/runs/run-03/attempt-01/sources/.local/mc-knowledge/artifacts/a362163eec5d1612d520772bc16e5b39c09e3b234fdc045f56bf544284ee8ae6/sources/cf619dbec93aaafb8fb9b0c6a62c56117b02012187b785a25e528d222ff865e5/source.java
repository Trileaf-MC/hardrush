package net.minecraft.world.entity.ai.goal;

import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal.Flag;

public class WrappedGoal extends Goal {
   public boolean canBeReplacedBy(final WrappedGoal goal) {
      return this.isInterruptable() && goal.getPriority() < this.getPriority();
   }
}
