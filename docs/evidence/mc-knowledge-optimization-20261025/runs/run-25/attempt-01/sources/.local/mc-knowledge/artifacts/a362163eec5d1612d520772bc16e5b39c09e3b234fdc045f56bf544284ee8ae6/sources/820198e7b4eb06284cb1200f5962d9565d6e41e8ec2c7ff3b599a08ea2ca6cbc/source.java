package net.minecraft.world.level.block.entity.trialspawner;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootTable;

public enum TrialSpawnerState implements StringRepresentable {
   TrialSpawnerState tickAndGetNext(final BlockPos spawnerPos, final TrialSpawner trialSpawner, final ServerLevel serverLevel) {
      TrialSpawnerStateData data = trialSpawner.getStateData();
      TrialSpawnerConfig config = trialSpawner.activeConfig();
      RandomSource random = serverLevel.getRandom();

      return switch (this) {
         case INACTIVE -> data.getOrCreateDisplayEntity(trialSpawner, serverLevel, WAITING_FOR_PLAYERS) == null ? this : WAITING_FOR_PLAYERS;
         case WAITING_FOR_PLAYERS -> {
            if (!trialSpawner.canSpawnInLevel(serverLevel)) {
               data.resetStatistics();
               yield this;
            } else if (!data.hasMobToSpawn(trialSpawner, random)) {
               yield INACTIVE;
            } else {
               data.tryDetectPlayers(serverLevel, spawnerPos, trialSpawner);
               yield data.detectedPlayers.isEmpty() ? this : ACTIVE;
            }
         }
         case ACTIVE -> {
            if (!trialSpawner.canSpawnInLevel(serverLevel)) {
               data.resetStatistics();
               yield WAITING_FOR_PLAYERS;
            } else if (!data.hasMobToSpawn(trialSpawner, random)) {
               yield INACTIVE;
            } else {
               int additionalPlayers = data.countAdditionalPlayers(spawnerPos);
               data.tryDetectPlayers(serverLevel, spawnerPos, trialSpawner);
               if (trialSpawner.isOminous()) {
                  this.spawnOminousOminousItemSpawner(serverLevel, spawnerPos, trialSpawner);
               }

               if (data.hasFinishedSpawningAllMobs(config, additionalPlayers)) {
                  if (data.haveAllCurrentMobsDied()) {
                     data.cooldownEndsAt = serverLevel.getGameTime() + trialSpawner.getTargetCooldownLength();
                     data.totalMobsSpawned = 0;
                     data.nextMobSpawnsAt = 0L;
                     yield WAITING_FOR_REWARD_EJECTION;
                  }
               } else if (data.isReadyToSpawnNextMob(serverLevel, config, additionalPlayers)) {
                  trialSpawner.spawnMob(serverLevel, spawnerPos).ifPresent(entityId -> {
                     data.currentMobs.add(entityId);
                     data.totalMobsSpawned++;
                     data.nextMobSpawnsAt = serverLevel.getGameTime() + config.ticksBetweenSpawn();
                     config.spawnPotentialsDefinition().getRandom(random).ifPresent(entry -> {
                        data.nextSpawnData = Optional.of(entry);
                        trialSpawner.markUpdated();
                     });
                  });
               }

               yield this;
            }
         }
         case WAITING_FOR_REWARD_EJECTION -> {
            if (data.isReadyToOpenShutter(serverLevel, 40.0F, trialSpawner.getTargetCooldownLength())) {
               serverLevel.playSound(null, spawnerPos, SoundEvents.TRIAL_SPAWNER_OPEN_SHUTTER, SoundSource.BLOCKS);
               yield EJECTING_REWARD;
            } else {
               yield this;
            }
         }
         case EJECTING_REWARD -> {
            if (!data.isReadyToEjectItems(serverLevel, TIME_BETWEEN_EACH_EJECTION, trialSpawner.getTargetCooldownLength())) {
               yield this;
            } else if (data.detectedPlayers.isEmpty()) {
               serverLevel.playSound(null, spawnerPos, SoundEvents.TRIAL_SPAWNER_CLOSE_SHUTTER, SoundSource.BLOCKS);
               data.ejectingLootTable = Optional.empty();
               yield COOLDOWN;
            } else {
               if (data.ejectingLootTable.isEmpty()) {
                  data.ejectingLootTable = config.lootTablesToEject().getRandom(random);
               }

               data.ejectingLootTable.ifPresent(lootTable -> trialSpawner.ejectReward(serverLevel, spawnerPos, (ResourceKey<LootTable>)lootTable));
               data.detectedPlayers.remove(data.detectedPlayers.iterator().next());
               yield this;
            }
         }
         case COOLDOWN -> {
            data.tryDetectPlayers(serverLevel, spawnerPos, trialSpawner);
            if (!data.detectedPlayers.isEmpty()) {
               data.totalMobsSpawned = 0;
               data.nextMobSpawnsAt = 0L;
               yield ACTIVE;
            } else if (data.isCooldownFinished(serverLevel)) {
               trialSpawner.removeOminous(serverLevel, spawnerPos);
               data.reset();
               yield WAITING_FOR_PLAYERS;
            } else {
               yield this;
            }
         }
      };
   }
}
