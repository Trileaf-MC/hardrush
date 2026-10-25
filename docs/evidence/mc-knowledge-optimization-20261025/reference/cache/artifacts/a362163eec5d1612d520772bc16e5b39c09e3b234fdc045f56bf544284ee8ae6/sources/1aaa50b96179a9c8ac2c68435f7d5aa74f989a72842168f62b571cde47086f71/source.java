package net.minecraft.world.level;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.component.TypedEntityData;

public abstract class BaseSpawner {
   private boolean isNearPlayer(final Level level, final BlockPos pos) {
      return level.hasNearbyAlivePlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, this.requiredPlayerRange);
   }
}
