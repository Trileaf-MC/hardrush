package net.minecraft.world.level.block.entity.trialspawner;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.storage.loot.LootTable;

public final class TrialSpawner {
   public void tickServer(final ServerLevel serverLevel, final BlockPos spawnerPos, final boolean isOminous) {
      this.isOminous = isOminous;
      TrialSpawnerState currentState = this.getState();
      if (this.data.currentMobs.removeIf(id -> shouldMobBeUntracked(serverLevel, spawnerPos, id))) {
         this.data.nextMobSpawnsAt = serverLevel.getGameTime() + this.activeConfig().ticksBetweenSpawn();
      }

      TrialSpawnerState nextState = currentState.tickAndGetNext(spawnerPos, this, serverLevel);
      if (nextState != currentState) {
         this.setState(serverLevel, nextState);
      }
   }
}
