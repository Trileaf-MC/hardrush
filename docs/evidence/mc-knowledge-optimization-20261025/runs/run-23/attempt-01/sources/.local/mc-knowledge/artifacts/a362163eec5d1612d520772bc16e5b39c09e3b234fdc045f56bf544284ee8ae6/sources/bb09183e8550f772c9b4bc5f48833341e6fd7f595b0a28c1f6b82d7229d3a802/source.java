package net.minecraft.world.level.block.entity.trialspawner;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.storage.loot.LootTable;

public final class TrialSpawner {
   private static boolean shouldMobBeUntracked(final ServerLevel serverLevel, final BlockPos spawnerPos, final UUID id) {
      Entity entity = serverLevel.getEntity(id);
      return entity == null
         || !entity.isAlive()
         || !entity.level().dimension().equals(serverLevel.dimension())
         || entity.blockPosition().distSqr(spawnerPos) > MAX_MOB_TRACKING_DISTANCE_SQR;
   }
}
