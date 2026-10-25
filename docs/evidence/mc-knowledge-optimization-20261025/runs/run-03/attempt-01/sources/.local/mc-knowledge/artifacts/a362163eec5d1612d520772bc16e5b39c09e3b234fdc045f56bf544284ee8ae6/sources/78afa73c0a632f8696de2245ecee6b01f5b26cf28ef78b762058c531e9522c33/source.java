package net.minecraft.world.entity.ai.goal;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.ai.goal.Goal.Flag;

public class GoalSelector {
   public void tick() {
      ProfilerFiller profiler = Profiler.get();
      profiler.push("goalCleanup");

      for (WrappedGoal goal : this.availableGoals) {
         if (goal.isRunning() && (goalContainsAnyFlags(goal, this.disabledFlags) || !goal.canContinueToUse())) {
            goal.stop();
         }
      }

      this.lockedFlags.entrySet().removeIf(entry -> !entry.getValue().isRunning());
      profiler.pop();
      profiler.push("goalUpdate");

      for (WrappedGoal goal : this.availableGoals) {
         if (!goal.isRunning() && !goalContainsAnyFlags(goal, this.disabledFlags) && goalCanBeReplacedForAllFlags(goal, this.lockedFlags) && goal.canUse()) {
            for (Flag flag : goal.getFlags()) {
               WrappedGoal currentGoal = this.lockedFlags.getOrDefault(flag, NO_GOAL);
               currentGoal.stop();
               this.lockedFlags.put(flag, goal);
            }

            goal.start();
         }
      }

      profiler.pop();
      this.tickRunningGoals(true);
   }
}
