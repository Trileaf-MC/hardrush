package net.minecraft.world.level;

import com.google.common.collect.ImmutableList.Builder;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.shapes.VoxelShape;

public interface EntityGetter {
   default boolean hasNearbyAlivePlayer(final double x, final double y, final double z, final double range) {
      for (Player player : this.players()) {
         if (EntitySelector.NO_SPECTATORS.test(player) && EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(player)) {
            double playerDist = player.distanceToSqr(x, y, z);
            if (range < 0.0 || playerDist < range * range) {
               return true;
            }
         }
      }

      return false;
   }
}
