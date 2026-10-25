package net.minecraft.world.level.block.entity.trialspawner;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentTable;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

public class TrialSpawnerConfigs {
   public static void bootstrap(final BootstrapContext<TrialSpawnerConfig> context) {
      register(
         context,
         TRIAL_CHAMBER_BREEZE,
         TrialSpawnerConfig.builder()
            .simultaneousMobs(1.0F)
            .simultaneousMobsAddedPerPlayer(0.5F)
            .ticksBetweenSpawn(20)
            .totalMobs(2.0F)
            .totalMobsAddedPerPlayer(1.0F)
            .spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.BREEZE)))
            .build(),
         TrialSpawnerConfig.builder()
            .simultaneousMobsAddedPerPlayer(0.5F)
            .ticksBetweenSpawn(20)
            .totalMobs(4.0F)
            .totalMobsAddedPerPlayer(1.0F)
            .spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.BREEZE)))
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_MELEE_HUSK,
         trialChamberBase().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.HUSK))).build(),
         trialChamberBase()
            .spawnPotentialsDefinition(WeightedList.of(spawnDataWithEquipment(EntityTypes.HUSK, BuiltInLootTables.EQUIPMENT_TRIAL_CHAMBER_MELEE)))
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_MELEE_SPIDER,
         trialChamberBase().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.SPIDER))).build(),
         trialChamberMeleeOminous()
            .spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.SPIDER)))
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_MELEE_ZOMBIE,
         trialChamberBase().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.ZOMBIE))).build(),
         trialChamberBase()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(WeightedList.of(spawnDataWithEquipment(EntityTypes.ZOMBIE, BuiltInLootTables.EQUIPMENT_TRIAL_CHAMBER_MELEE)))
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_RANGED_POISON_SKELETON,
         trialChamberBase().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.BOGGED))).build(),
         trialChamberBase()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(WeightedList.of(spawnDataWithEquipment(EntityTypes.BOGGED, BuiltInLootTables.EQUIPMENT_TRIAL_CHAMBER_RANGED)))
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_RANGED_SKELETON,
         trialChamberBase().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.SKELETON))).build(),
         trialChamberBase()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(WeightedList.of(spawnDataWithEquipment(EntityTypes.SKELETON, BuiltInLootTables.EQUIPMENT_TRIAL_CHAMBER_RANGED)))
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_RANGED_STRAY,
         trialChamberBase().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.STRAY))).build(),
         trialChamberBase()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(WeightedList.of(spawnDataWithEquipment(EntityTypes.STRAY, BuiltInLootTables.EQUIPMENT_TRIAL_CHAMBER_RANGED)))
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_SLOW_RANGED_POISON_SKELETON,
         trialChamberSlowRanged().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.BOGGED))).build(),
         trialChamberSlowRanged()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(WeightedList.of(spawnDataWithEquipment(EntityTypes.BOGGED, BuiltInLootTables.EQUIPMENT_TRIAL_CHAMBER_RANGED)))
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_SLOW_RANGED_SKELETON,
         trialChamberSlowRanged().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.SKELETON))).build(),
         trialChamberSlowRanged()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(WeightedList.of(spawnDataWithEquipment(EntityTypes.SKELETON, BuiltInLootTables.EQUIPMENT_TRIAL_CHAMBER_RANGED)))
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_SLOW_RANGED_STRAY,
         trialChamberSlowRanged().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.STRAY))).build(),
         trialChamberSlowRanged()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(WeightedList.of(spawnDataWithEquipment(EntityTypes.STRAY, BuiltInLootTables.EQUIPMENT_TRIAL_CHAMBER_RANGED)))
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_SMALL_MELEE_BABY_ZOMBIE,
         TrialSpawnerConfig.builder()
            .simultaneousMobsAddedPerPlayer(0.5F)
            .ticksBetweenSpawn(20)
            .spawnPotentialsDefinition(WeightedList.of(customSpawnDataWithEquipment(EntityTypes.ZOMBIE, tag -> tag.putBoolean("IsBaby", true), null)))
            .build(),
         TrialSpawnerConfig.builder()
            .simultaneousMobsAddedPerPlayer(0.5F)
            .ticksBetweenSpawn(20)
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(
               WeightedList.of(
                  customSpawnDataWithEquipment(EntityTypes.ZOMBIE, tag -> tag.putBoolean("IsBaby", true), BuiltInLootTables.EQUIPMENT_TRIAL_CHAMBER_MELEE)
               )
            )
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_SMALL_MELEE_CAVE_SPIDER,
         trialChamberBase().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.CAVE_SPIDER))).build(),
         trialChamberMeleeOminous()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.CAVE_SPIDER)))
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_SMALL_MELEE_SILVERFISH,
         trialChamberBase().spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.SILVERFISH))).build(),
         trialChamberMeleeOminous()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(WeightedList.of(spawnData(EntityTypes.SILVERFISH)))
            .build()
      );
      register(
         context,
         TRIAL_CHAMBER_SMALL_MELEE_SLIME,
         trialChamberBase()
            .spawnPotentialsDefinition(
               WeightedList.<SpawnData>builder()
                  .add(customSpawnData(EntityTypes.SLIME, tag -> tag.putByte("Size", (byte)1)), 3)
                  .add(customSpawnData(EntityTypes.SLIME, tag -> tag.putByte("Size", (byte)2)), 1)
                  .build()
            )
            .build(),
         trialChamberMeleeOminous()
            .lootTablesToEject(
               WeightedList.<ResourceKey<LootTable>>builder()
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_KEY, 3)
                  .add(BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES, 7)
                  .build()
            )
            .spawnPotentialsDefinition(
               WeightedList.<SpawnData>builder()
                  .add(customSpawnData(EntityTypes.SLIME, tag -> tag.putByte("Size", (byte)1)), 3)
                  .add(customSpawnData(EntityTypes.SLIME, tag -> tag.putByte("Size", (byte)2)), 1)
                  .build()
            )
            .build()
      );
   }
}
