package net.minecraft.world.level;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.storage.ValueInput;
import org.jspecify.annotations.Nullable;

public abstract class BaseSpawner {
   public void load(final @Nullable Level level, final BlockPos pos, final ValueInput input) {
      this.spawnDelay = input.getShortOr("Delay", (short)20);
      input.<SpawnData>read("SpawnData", SpawnData.CODEC).ifPresent(nextSpawnData -> this.setNextSpawnData(level, pos, nextSpawnData));
      this.spawnPotentials = input.<WeightedList<SpawnData>>read("SpawnPotentials", SpawnData.LIST_CODEC)
         .orElseGet(() -> WeightedList.of(this.nextSpawnData != null ? this.nextSpawnData : new SpawnData()));
      this.minSpawnDelay = input.getIntOr("MinSpawnDelay", 200);
      this.maxSpawnDelay = input.getIntOr("MaxSpawnDelay", 800);
      this.spawnCount = input.getIntOr("SpawnCount", 4);
      this.maxNearbyEntities = input.getIntOr("MaxNearbyEntities", 6);
      this.requiredPlayerRange = input.getIntOr("RequiredPlayerRange", 16);
      this.spawnRange = input.getIntOr("SpawnRange", 4);
      this.displayEntity = null;
   }
}
