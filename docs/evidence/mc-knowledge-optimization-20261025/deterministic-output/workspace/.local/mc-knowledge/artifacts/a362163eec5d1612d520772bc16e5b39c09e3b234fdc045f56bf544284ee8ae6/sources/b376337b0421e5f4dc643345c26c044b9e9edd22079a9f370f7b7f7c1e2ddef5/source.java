package net.minecraft.world.entity.monster.skeleton;

import net.minecraft.world.entity.EntityType;

public class Skeleton extends AbstractSkeleton {
   @Override
   public void tick() {
      this.freezingTracker.tick();
      super.tick();
   }
}
