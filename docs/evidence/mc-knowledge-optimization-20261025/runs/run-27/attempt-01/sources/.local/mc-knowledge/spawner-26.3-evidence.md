# Java 26.3 原版刷怪笼：本次查询的原始证据摘录

仅汇集本次 mc-knowledge 查询生成的原始片段及来源，不包含运行时实验。

## 版本与制品

原始出处：[证据 JSON](evidence/e5c22e463fc10ed3bc34d97775d397dfb18a9cd777194bcc6ecbf9727ddd93af.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
{
  "id": "26.3",
  "name": "26.3",
  "world_version": 5023,
  "series_id": "main",
  "protocol_version": 777,
  "pack_version": {
    "resource_major": 97,
    "resource_minor": 1,
    "data_major": 121,
    "data_minor": 0
  },
  "build_time": "2026-09-15T11:20:48+00:00",
  "java_component": "java-runtime-epsilon",
  "java_version": 25,
  "stable": true,
  "use_editor": false
}
```

## 普通刷怪笼默认值与玩家检测

原始出处：[证据 JSON](evidence/5c0a1e3ece22b4b89913a51af3d3513a552661deecc7a84ff4d07a7114ad3de7.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
package net.minecraft.world.level;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.ProblemReporter.ScopedCollector;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.SpawnData.CustomSpawnRules;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class BaseSpawner {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int SPAWNER_ENTITY_ID = -1;
   public static final EntityProcessor SET_DISPLAY_ENTITY_ID = e -> {
      e.setId(-1);
      return e;
   };
   public static final String SPAWN_DATA_TAG = "SpawnData";
   private static final int EVENT_SPAWN = 1;
   private static final int DEFAULT_SPAWN_DELAY = 20;
   private static final int DEFAULT_MIN_SPAWN_DELAY = 200;
   private static final int DEFAULT_MAX_SPAWN_DELAY = 800;
   private static final int DEFAULT_SPAWN_COUNT = 4;
   private static final int DEFAULT_MAX_NEARBY_ENTITIES = 6;
   private static final int DEFAULT_REQUIRED_PLAYER_RANGE = 16;
   private static final int DEFAULT_SPAWN_RANGE = 4;
   private int spawnDelay = 20;
   private WeightedList<SpawnData> spawnPotentials = WeightedList.of();
   private @Nullable SpawnData nextSpawnData;
   private double spin;
   private double oSpin;
   private int minSpawnDelay = 200;
   private int maxSpawnDelay = 800;
   private int spawnCount = 4;
   private @Nullable Entity displayEntity;
   private int maxNearbyEntities = 6;
   private int requiredPlayerRange = 16;
   private int spawnRange = 4;

   public void setEntityId(final EntityType<?> type, final @Nullable Level level, final RandomSource random, final BlockPos pos) {
      this.getOrCreateNextSpawnData(level, random, pos).getEntityToSpawn().putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
   }

   public void setEntityData(final TypedEntityData<EntityType<?>> entityData, final @Nullable Level level, final RandomSource random, final BlockPos pos) {
      SpawnData spawnData = this.getOrCreateNextSpawnData(level, random, pos);
      entityData.loadInto(spawnData, BuiltInRegistries.ENTITY_TYPE);
   }

   private boolean isNearPlayer(final Level level, final BlockPos pos) {
      return level.hasNearbyAlivePlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, this.requiredPlayerRange);
   }

   public void clientTick(final Level level, final BlockPos pos) {
      if (!this.isNearPlayer(level, pos)) {
         this.oSpin = this.spin;
      } else if (this.displayEntity != null) {
         RandomSource random = level.getRandom();
         double xP = pos.getX() + random.nextDouble();
         double yP = pos.getY() + random.nextDouble();
         double zP = pos.getZ() + random.nextDouble();
         level.addParticle(ParticleTypes.SMOKE, xP, yP, zP, 0.0, 0.0, 0.0);
         level.addParticle(ParticleTypes.FLAME, xP, yP, zP, 0.0, 0.0, 0.0);
         if (this.spawnDelay > 0) {
            this.spawnDelay--;
         }

         this.oSpin = this.spin;
         this.spin = (this.spin + 1000.0F / (this.spawnDelay + 200.0F)) % 360.0;
      }
   }

   public void serverTick(final ServerLevel level, final BlockPos pos) {
      if (this.isNearPlayer(level, pos) && level.isSpawnerBlockEnabled()) {
         if (this.spawnDelay == -1) {
            this.delay(level, pos);
         }

         if (this.spawnDelay > 0) {
            this.spawnDelay--;
         } else {
            boolean delay = false;
            RandomSource random = level.getRandom();
            SpawnData nextSpawnData = this.getOrCreateNextSpawnData(level, random, pos);

            for (int c = 0; c < this.spawnCount; c++) {
               try (ScopedCollector reporter = new ScopedCollector(this::toString, LOGGER)) {
                  ValueInput input = TagValueInput.create(reporter, level.registryAccess(), nextSpawnData.getEntityToSpawn());
                  Optional<EntityType<?>> entityType = EntityType.by(input);
                  if (entityType.isEmpty()) {
                     this.delay(level, pos);
                     return;
                  }

                  Vec3 spawnPos = input.<Vec3>read("Pos", Vec3.CODEC)
                     .orElseGet(
                        () -> new Vec3(
                           pos.getX() + (random.nextDouble() - random.nextDouble()) * this.spawnRange + 0.5,
                           pos.getY() + random.nextInt(3) - 1,
                           pos.getZ() + (random.nextDouble() - random.nextDouble()) * this.spawnRange + 0.5
                        )
                     );
                  if (level.noCollision(entityType.get().getSpawnAABB(spawnPos.x, spawnPos.y, spawnPos.z))) {
                     BlockPos spawnBlockPos = BlockPos.containing(spawnPos);
                     if (nextSpawnData.getCustomSpawnRules().isPresent()) {
                        if (!entityType.get().getCategory().isFriendly() && level.getDifficulty() == Difficulty.PEACEFUL) {
                           continue;
                        }

                        CustomSpawnRules customSpawnRules = nextSpawnData.getCustomSpawnRules().get();
                        if (!customSpawnRules.isValidPosition(spawnBlockPos, level)) {
                           continue;
                        }
                     } else if (!SpawnPlacements.checkSpawnRules(entityType.get(), level, EntitySpawnReason.SPAWNER, spawnBlockPos, level.getRandom())) {
                        continue;
                     }

                     Entity entity = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.SPAWNER, e -> {
                        e.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, e.getYRot(), e.getXRot());
                        return e;
                     });
                     if (entity == null) {
                        this.delay(level, pos);
                        return;
                     }

                     int nearBy = level.getEntities(
                           EntityTypeTest.forExactClass(entity.getClass()),
                           new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1).inflate(this.spawnRange),
                           EntitySelector.NO_SPECTATORS
                        )
                        .size();
                     if (nearBy >= this.maxNearbyEntities) {
                        this.delay(level, pos);
                        return;
                     }

                     entity.snapTo(entity.getX(), entity.getY(), entity.getZ(), random.nextFloat() * 360.0F, 0.0F);
                     if (entity instanceof Mob mob) {
                        if (nextSpawnData.getCustomSpawnRules().isEmpty() && !mob.checkSpawnRules(level, EntitySpawnReason.SPAWNER)
                           || !mob.checkSpawnObstruction(level)) {
                           continue;
                        }

                        boolean hasNoConfiguration = nextSpawnData.getEntityToSpawn().size() == 1
                           && nextSpawnData.getEntityToSpawn().getString("id").isPresent();
                        if (hasNoConfiguration) {
                           ((Mob)entity).finalizeSpawn(level, level.getCurrentDifficultyAt(entity.blockPosition()), EntitySpawnReason.SPAWNER, null);
                        }

                        nextSpawnData.getEquipment().ifPresent(mob::equip);
                     }

                     if (!level.tryAddFreshEntityWithPassengers(entity)) {
                        this.delay(level, pos);
                        return;
                     }

                     level.levelEvent(2004, pos, 0);
                     level.gameEvent(entity, GameEvent.ENTITY_PLACE, spawnBlockPos);
                     if (entity instanceof Mob mob) {
                        mob.spawnAnim();
                     }

                     delay = true;
                  }
               }
            }
```

## 普通刷怪笼完整 serverTick

原始出处：[证据 JSON](evidence/ce9491e58d0cb2ba3baee7001b49c36af9ac9db55f6531fd2f0ca5a697d71c20.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
public abstract class BaseSpawner {
   public void serverTick(final ServerLevel level, final BlockPos pos) {
      if (this.isNearPlayer(level, pos) && level.isSpawnerBlockEnabled()) {
         if (this.spawnDelay == -1) {
            this.delay(level, pos);
         }

         if (this.spawnDelay > 0) {
            this.spawnDelay--;
         } else {
            boolean delay = false;
            RandomSource random = level.getRandom();
            SpawnData nextSpawnData = this.getOrCreateNextSpawnData(level, random, pos);

            for (int c = 0; c < this.spawnCount; c++) {
               try (ScopedCollector reporter = new ScopedCollector(this::toString, LOGGER)) {
                  ValueInput input = TagValueInput.create(reporter, level.registryAccess(), nextSpawnData.getEntityToSpawn());
                  Optional<EntityType<?>> entityType = EntityType.by(input);
                  if (entityType.isEmpty()) {
                     this.delay(level, pos);
                     return;
                  }

                  Vec3 spawnPos = input.<Vec3>read("Pos", Vec3.CODEC)
                     .orElseGet(
                        () -> new Vec3(
                           pos.getX() + (random.nextDouble() - random.nextDouble()) * this.spawnRange + 0.5,
                           pos.getY() + random.nextInt(3) - 1,
                           pos.getZ() + (random.nextDouble() - random.nextDouble()) * this.spawnRange + 0.5
                        )
                     );
                  if (level.noCollision(entityType.get().getSpawnAABB(spawnPos.x, spawnPos.y, spawnPos.z))) {
                     BlockPos spawnBlockPos = BlockPos.containing(spawnPos);
                     if (nextSpawnData.getCustomSpawnRules().isPresent()) {
                        if (!entityType.get().getCategory().isFriendly() && level.getDifficulty() == Difficulty.PEACEFUL) {
                           continue;
                        }

                        CustomSpawnRules customSpawnRules = nextSpawnData.getCustomSpawnRules().get();
                        if (!customSpawnRules.isValidPosition(spawnBlockPos, level)) {
                           continue;
                        }
                     } else if (!SpawnPlacements.checkSpawnRules(entityType.get(), level, EntitySpawnReason.SPAWNER, spawnBlockPos, level.getRandom())) {
                        continue;
                     }

                     Entity entity = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.SPAWNER, e -> {
                        e.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, e.getYRot(), e.getXRot());
                        return e;
                     });
                     if (entity == null) {
                        this.delay(level, pos);
                        return;
                     }

                     int nearBy = level.getEntities(
                           EntityTypeTest.forExactClass(entity.getClass()),
                           new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1).inflate(this.spawnRange),
                           EntitySelector.NO_SPECTATORS
                        )
                        .size();
                     if (nearBy >= this.maxNearbyEntities) {
                        this.delay(level, pos);
                        return;
                     }

                     entity.snapTo(entity.getX(), entity.getY(), entity.getZ(), random.nextFloat() * 360.0F, 0.0F);
                     if (entity instanceof Mob mob) {
                        if (nextSpawnData.getCustomSpawnRules().isEmpty() && !mob.checkSpawnRules(level, EntitySpawnReason.SPAWNER)
                           || !mob.checkSpawnObstruction(level)) {
                           continue;
                        }

                        boolean hasNoConfiguration = nextSpawnData.getEntityToSpawn().size() == 1
                           && nextSpawnData.getEntityToSpawn().getString("id").isPresent();
                        if (hasNoConfiguration) {
                           ((Mob)entity).finalizeSpawn(level, level.getCurrentDifficultyAt(entity.blockPosition()), EntitySpawnReason.SPAWNER, null);
                        }

                        nextSpawnData.getEquipment().ifPresent(mob::equip);
                     }

                     if (!level.tryAddFreshEntityWithPassengers(entity)) {
                        this.delay(level, pos);
                        return;
                     }

                     level.levelEvent(2004, pos, 0);
                     level.gameEvent(entity, GameEvent.ENTITY_PLACE, spawnBlockPos);
                     if (entity instanceof Mob mob) {
                        mob.spawnAnim();
                     }

                     delay = true;
                  }
               }
            }

            if (delay) {
               this.delay(level, pos);
            }

            return;
         }
      }
   }
}
```

## 普通刷怪笼随机延迟

原始出处：[证据 JSON](evidence/a69da106e8866494af283569f4a9ff6380eea803070382363b0d4707bd97e939.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
public abstract class BaseSpawner {
   private void delay(final Level level, final BlockPos pos) {
      RandomSource random = level.random;
      if (this.maxSpawnDelay <= this.minSpawnDelay) {
         this.spawnDelay = this.minSpawnDelay;
      } else {
         this.spawnDelay = this.minSpawnDelay + random.nextInt(this.maxSpawnDelay - this.minSpawnDelay);
      }

      this.spawnPotentials.getRandom(random).ifPresent(entry -> this.setNextSpawnData(level, pos, entry));
      this.broadcastEvent(level, pos, 1);
   }
}
```

## 普通玩家距离与模式条件

原始出处：[证据 JSON](evidence/e9d3837378c8cca08671048e8079a02c9582ba3fa1943124a688b0fbf7a1d731.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
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
```

## 试炼状态机与完成后冷却

原始出处：[证据 JSON](evidence/e0d434c722a902812eb091e461f1376199fb775c0e45d43ccda730f67714054f.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
package net.minecraft.world.level.block.entity.trialspawner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OminousItemSpawner;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

public enum TrialSpawnerState implements StringRepresentable {
   INACTIVE("inactive", 0, TrialSpawnerState.ParticleEmission.NONE, -1.0, false),
   WAITING_FOR_PLAYERS("waiting_for_players", 4, TrialSpawnerState.ParticleEmission.SMALL_FLAMES, 200.0, true),
   ACTIVE("active", 8, TrialSpawnerState.ParticleEmission.FLAMES_AND_SMOKE, 1000.0, true),
   WAITING_FOR_REWARD_EJECTION("waiting_for_reward_ejection", 8, TrialSpawnerState.ParticleEmission.SMALL_FLAMES, -1.0, false),
   EJECTING_REWARD("ejecting_reward", 8, TrialSpawnerState.ParticleEmission.SMALL_FLAMES, -1.0, false),
   COOLDOWN("cooldown", 0, TrialSpawnerState.ParticleEmission.SMOKE_INSIDE_AND_TOP_FACE, -1.0, false);

   private static final float DELAY_BEFORE_EJECT_AFTER_KILLING_LAST_MOB = 40.0F;
   private static final int TIME_BETWEEN_EACH_EJECTION = Mth.floor(30.0F);
   private final String name;
   private final int lightLevel;
   private final double spinningMobSpeed;
   private final TrialSpawnerState.ParticleEmission particleEmission;
   private final boolean isCapableOfSpawning;

   TrialSpawnerState(
      final String name,
      final int lightLevel,
      final TrialSpawnerState.ParticleEmission particleEmission,
      final double spinningMobSpeed,
      final boolean isCapableOfSpawning
   ) {
      this.name = name;
      this.lightLevel = lightLevel;
      this.particleEmission = particleEmission;
      this.spinningMobSpeed = spinningMobSpeed;
      this.isCapableOfSpawning = isCapableOfSpawning;
   }

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

   private void spawnOminousOminousItemSpawner(final ServerLevel level, final BlockPos trialSpawnerPos, final TrialSpawner trialSpawner) {
      TrialSpawnerStateData data = trialSpawner.getStateData();
      TrialSpawnerConfig config = trialSpawner.activeConfig();
      ItemStack itemToDispense = data.getDispensingItems(level, config, trialSpawnerPos).getRandom(level.getRandom()).orElse(ItemStack.EMPTY);
      if (!itemToDispense.isEmpty()) {
         if (this.timeToSpawnItemSpawner(level, data)) {
            calculatePositionToSpawnSpawner(level, trialSpawnerPos, trialSpawner, data).ifPresent(pos -> {
               OminousItemSpawner itemSpawner = OminousItemSpawner.create(level, itemToDispense);
               itemSpawner.snapTo(pos);
               level.addFreshEntity(itemSpawner);
               float pitch = (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.2F + 1.0F;
               level.playSound(null, BlockPos.containing(pos), SoundEvents.TRIAL_SPAWNER_SPAWN_ITEM_BEGIN, SoundSource.BLOCKS, 1.0F, pitch);
               data.cooldownEndsAt = level.getGameTime() + trialSpawner.ominousConfig().ticksBetweenItemSpawners();
            });
         }
      }
   }

   private static Optional<Vec3> calculatePositionToSpawnSpawner(
      final ServerLevel level, final BlockPos trialSpawnerPos, final TrialSpawner trialSpawner, final TrialSpawnerStateData data
   ) {
      List<Player> nearbyPlayers = data.detectedPlayers
         .stream()
         .map(level::getPlayerByUUID)
         .filter(Objects::nonNull)
         .filter(
            player -> !player.isCreative()
               && !player.isSpectator()
               && player.isAlive()
               && player.distanceToSqr(Vec3.atCenterOf(trialSpawnerPos)) <= Mth.square(trialSpawner.getRequiredPlayerRange())
         )
         .toList();
      if (nearbyPlayers.isEmpty()) {
         return Optional.empty();
      }

      Entity entity = selectEntityToSpawnItemAbove(nearbyPlayers, data.currentMobs, trialSpawner, trialSpawnerPos, level);
      return entity == null ? Optional.empty() : calculatePositionAbove(entity, level);
   }

   private static Optional<Vec3> calculatePositionAbove(final Entity entityToSpawnItemAbove, final ServerLevel level) {
      Vec3 entityPos = entityToSpawnItemAbove.position();
      Vec3 trySpawnPos = entityPos.relative(Direction.UP, entityToSpawnItemAbove.getBbHeight() + 2.0F + level.getRandom().nextInt(4));
      BlockHitResult hitResult = level.clip(new ClipContext(entityPos, trySpawnPos, Block.VISUAL, Fluid.NONE, CollisionContext.empty()));
      Vec3 down = Vec3.atCenterOf(hitResult.getBlockPos()).relative(Direction.DOWN, 1.0);
      BlockPos blockPosDown = BlockPos.containing(down);
      return !level.getBlockState(blockPosDown).getCollisionShape(level, blockPosDown).isEmpty() ? Optional.empty() : Optional.of(down);
   }

   private static @Nullable Entity selectEntityToSpawnItemAbove(
      final List<Player> nearbyPlayers, final Set<UUID> mobIds, final TrialSpawner trialSpawner, final BlockPos spawnerPos, final ServerLevel level
```

## 试炼计时、追踪数量、玩家登记

原始出处：[证据 JSON](evidence/bb26abf7094872d9280b63ac14565f73ba138bcb1974b8aecbf13ab8392ed921.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
package net.minecraft.world.level.block.entity.trialspawner;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.random.WeightedList.Builder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner.FlameParticle;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import org.jspecify.annotations.Nullable;

public class TrialSpawnerStateData {
   private static final String TAG_SPAWN_DATA = "spawn_data";
   private static final String TAG_NEXT_MOB_SPAWNS_AT = "next_mob_spawns_at";
   private static final int DELAY_BETWEEN_PLAYER_SCANS = 20;
   private static final int TRIAL_OMEN_PER_BAD_OMEN_LEVEL = 18000;
   final Set<UUID> detectedPlayers = new HashSet<>();
   final Set<UUID> currentMobs = new HashSet<>();
   long cooldownEndsAt;
   long nextMobSpawnsAt;
   int totalMobsSpawned;
   Optional<SpawnData> nextSpawnData = Optional.empty();
   Optional<ResourceKey<LootTable>> ejectingLootTable = Optional.empty();
   private @Nullable Entity displayEntity;
   private @Nullable WeightedList<ItemStack> dispensing;
   double spin;
   double oSpin;

   public TrialSpawnerStateData.Packed pack() {
      return new TrialSpawnerStateData.Packed(
         Set.copyOf(this.detectedPlayers),
         Set.copyOf(this.currentMobs),
         this.cooldownEndsAt,
         this.nextMobSpawnsAt,
         this.totalMobsSpawned,
         this.nextSpawnData,
         this.ejectingLootTable
      );
   }

   public void apply(final TrialSpawnerStateData.Packed packed) {
      this.detectedPlayers.clear();
      this.detectedPlayers.addAll(packed.detectedPlayers);
      this.currentMobs.clear();
      this.currentMobs.addAll(packed.currentMobs);
      this.cooldownEndsAt = packed.cooldownEndsAt;
      this.nextMobSpawnsAt = packed.nextMobSpawnsAt;
      this.totalMobsSpawned = packed.totalMobsSpawned;
      this.nextSpawnData = packed.nextSpawnData;
      this.ejectingLootTable = packed.ejectingLootTable;
   }

   public void reset() {
      this.currentMobs.clear();
      this.nextSpawnData = Optional.empty();
      this.resetStatistics();
   }

   public void resetStatistics() {
      this.detectedPlayers.clear();
      this.totalMobsSpawned = 0;
      this.nextMobSpawnsAt = 0L;
      this.cooldownEndsAt = 0L;
   }

   public boolean hasMobToSpawn(final TrialSpawner trialSpawner, final RandomSource random) {
      boolean hasNextMobToSpawn = this.getOrCreateNextSpawnData(trialSpawner, random).getEntityToSpawn().getString("id").isPresent();
      return hasNextMobToSpawn || !trialSpawner.activeConfig().spawnPotentialsDefinition().isEmpty();
   }

   public boolean hasFinishedSpawningAllMobs(final TrialSpawnerConfig config, final int additionalPlayers) {
      return this.totalMobsSpawned >= config.calculateTargetTotalMobs(additionalPlayers);
   }

   public boolean haveAllCurrentMobsDied() {
      return this.currentMobs.isEmpty();
   }

   public boolean isReadyToSpawnNextMob(final ServerLevel serverLevel, final TrialSpawnerConfig config, final int additionalPlayers) {
      return serverLevel.getGameTime() >= this.nextMobSpawnsAt && this.currentMobs.size() < config.calculateTargetSimultaneousMobs(additionalPlayers);
   }

   public int countAdditionalPlayers(final BlockPos pos) {
      if (this.detectedPlayers.isEmpty()) {
         Util.logAndPauseIfInIde("Trial Spawner at " + pos + " has no detected players");
      }

      return Math.max(0, this.detectedPlayers.size() - 1);
   }

   public void tryDetectPlayers(final ServerLevel level, final BlockPos pos, final TrialSpawner trialSpawner) {
      boolean isThrottled = (pos.asLong() + level.getGameTime()) % 20L != 0L;
      if (!isThrottled) {
         if (!trialSpawner.getState().equals(TrialSpawnerState.COOLDOWN) || !trialSpawner.isOminous()) {
            List<UUID> inLineOfSightPlayers = trialSpawner.getPlayerDetector()
               .detect(level, trialSpawner.getEntitySelector(), pos, trialSpawner.getRequiredPlayerRange(), true);
            boolean becameOminous;
            if (!trialSpawner.isOminous() && !inLineOfSightPlayers.isEmpty()) {
               Optional<Pair<Player, Holder<MobEffect>>> playerWithOminousEffect = findPlayerWithOminousEffect(level, inLineOfSightPlayers);
               playerWithOminousEffect.ifPresent(playerAndEffect -> {
                  Player player = (Player)playerAndEffect.getFirst();
                  if (playerAndEffect.getSecond() == MobEffects.BAD_OMEN) {
                     transformBadOmenIntoTrialOmen(player);
                  }

                  level.levelEvent(3020, BlockPos.containing(player.getEyePosition()), 0);
                  trialSpawner.applyOminous(level, pos);
               });
               becameOminous = playerWithOminousEffect.isPresent();
            } else {
               becameOminous = false;
            }

            if (!trialSpawner.getState().equals(TrialSpawnerState.COOLDOWN) || becameOminous) {
               boolean isSearchingForFirstPlayer = trialSpawner.getStateData().detectedPlayers.isEmpty();
               List<UUID> foundPlayers = isSearchingForFirstPlayer
                  ? inLineOfSightPlayers
                  : trialSpawner.getPlayerDetector().detect(level, trialSpawner.getEntitySelector(), pos, trialSpawner.getRequiredPlayerRange(), false);
               if (this.detectedPlayers.addAll(foundPlayers)) {
                  this.nextMobSpawnsAt = Math.max(level.getGameTime() + 40L, this.nextMobSpawnsAt);
                  if (!becameOminous) {
                     int event = trialSpawner.isOminous() ? 3019 : 3013;
                     level.levelEvent(event, pos, this.detectedPlayers.size());
                  }
               }
            }
         }
      }
   }

   private static Optional<Pair<Player, Holder<MobEffect>>> findPlayerWithOminousEffect(final ServerLevel level, final List<UUID> inLineOfSightPlayers) {
      Player playerWithBadOmen = null;

      for (UUID playerUuid : inLineOfSightPlayers) {
         Player player = level.getPlayerByUUID(playerUuid);
         if (player != null) {
            Holder<MobEffect> trialOmen = MobEffects.TRIAL_OMEN;
            if (player.hasEffect(trialOmen)) {
               return Optional.of(Pair.of(player, trialOmen));
            }

            if (player.hasEffect(MobEffects.BAD_OMEN)) {
               playerWithBadOmen = player;
            }
         }
      }

      return Optional.ofNullable(playerWithBadOmen).map(playerx -> Pair.of(playerx, MobEffects.BAD_OMEN));
   }

   public void resetAfterBecomingOminous(final TrialSpawner trialSpawner, final ServerLevel level) {
      this.currentMobs.stream().map(level::getEntity).forEach(entity -> {
         if (entity != null) {
            level.levelEvent(3012, entity.blockPosition(), FlameParticle.NORMAL.encode());
            if (entity instanceof Mob mob) {
               mob.dropPreservedEquipment(level);
            }

            entity.remove(RemovalReason.DISCARDED);
         }
      });
      if (!trialSpawner.ominousConfig().spawnPotentialsDefinition().isEmpty()) {
         this.nextSpawnData = Optional.empty();
      }

      this.totalMobsSpawned = 0;
      this.currentMobs.clear();
      this.nextMobSpawnsAt = level.getGameTime() + trialSpawner.ominousConfig().ticksBetweenSpawn();
      trialSpawner.markUpdated();
      this.cooldownEndsAt = level.getGameTime() + trialSpawner.ominousConfig().ticksBetweenItemSpawners();
   }

   private static void transformBadOmenIntoTrialOmen(final Player player) {
      MobEffectInstance badOmen = player.getEffect(MobEffects.BAD_OMEN);
      if (badOmen != null) {
         int amplifier = badOmen.getAmplifier() + 1;
         int duration = 18000 * amplifier;
         player.removeEffect(MobEffects.BAD_OMEN);
         player.addEffect(new MobEffectInstance(MobEffects.TRIAL_OMEN, duration, 0));
      }
   }

   public boolean isReadyToOpenShutter(final ServerLevel serverLevel, final float delayBeforeOpen, final int targetCooldownLength) {
      long cooldownStartedAt = this.cooldownEndsAt - targetCooldownLength;
      return (float)serverLevel.getGameTime() >= (float)cooldownStartedAt + delayBeforeOpen;
   }

   public boolean isReadyToEjectItems(final ServerLevel serverLevel, final float timeBetweenEjections, final int targetCooldownLength) {
      long cooldownStartedAt = this.cooldownEndsAt - targetCooldownLength;
      return (float)(serverLevel.getGameTime() - cooldownStartedAt) % timeBetweenEjections == 0.0F;
   }

   public boolean isCooldownFinished(final ServerLevel serverLevel) {
      return serverLevel.getGameTime() >= this.cooldownEndsAt;
   }

   protected SpawnData getOrCreateNextSpawnData(final TrialSpawner trialSpawner, final RandomSource random) {
      if (this.nextSpawnData.isPresent()) {
         return this.nextSpawnData.get();
      }

      WeightedList<SpawnData> spawnPotentials = trialSpawner.activeConfig().spawnPotentialsDefinition();
      Optional<SpawnData> selected = spawnPotentials.isEmpty() ? this.nextSpawnData : spawnPotentials.getRandom(random);
      this.nextSpawnData = Optional.of(selected.orElseGet(SpawnData::new));
      trialSpawner.markUpdated();
      return this.nextSpawnData.get();
   }
```

## 试炼通用配置默认值与数量公式

原始出处：[证据 JSON](evidence/14c0374e9ddaf2eb59a931f0f5432d8c4248c86c4bb7c793fc0775c6fff67603.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
package net.minecraft.world.level.block.entity.trialspawner;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.codec.RegistryCodecs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

public record TrialSpawnerConfig(
   int spawnRange,
   float totalMobs,
   float simultaneousMobs,
   float totalMobsAddedPerPlayer,
   float simultaneousMobsAddedPerPlayer,
   int ticksBetweenSpawn,
   WeightedList<SpawnData> spawnPotentialsDefinition,
   WeightedList<ResourceKey<LootTable>> lootTablesToEject,
   ResourceKey<LootTable> itemsToDropWhenOminous
) {
   public static final TrialSpawnerConfig DEFAULT = builder().build();
   public static final Codec<TrialSpawnerConfig> DIRECT_CODEC = RecordCodecBuilder.create(
      i -> i.group(
            Codec.intRange(1, 128).optionalFieldOf("spawn_range", DEFAULT.spawnRange).forGetter(TrialSpawnerConfig::spawnRange),
            Codec.floatRange(0.0F, Float.MAX_VALUE).optionalFieldOf("total_mobs", DEFAULT.totalMobs).forGetter(TrialSpawnerConfig::totalMobs),
            Codec.floatRange(0.0F, Float.MAX_VALUE)
               .optionalFieldOf("simultaneous_mobs", DEFAULT.simultaneousMobs)
               .forGetter(TrialSpawnerConfig::simultaneousMobs),
            Codec.floatRange(0.0F, Float.MAX_VALUE)
               .optionalFieldOf("total_mobs_added_per_player", DEFAULT.totalMobsAddedPerPlayer)
               .forGetter(TrialSpawnerConfig::totalMobsAddedPerPlayer),
            Codec.floatRange(0.0F, Float.MAX_VALUE)
               .optionalFieldOf("simultaneous_mobs_added_per_player", DEFAULT.simultaneousMobsAddedPerPlayer)
               .forGetter(TrialSpawnerConfig::simultaneousMobsAddedPerPlayer),
            Codec.intRange(0, Integer.MAX_VALUE)
               .optionalFieldOf("ticks_between_spawn", DEFAULT.ticksBetweenSpawn)
               .forGetter(TrialSpawnerConfig::ticksBetweenSpawn),
            SpawnData.LIST_CODEC.optionalFieldOf("spawn_potentials", WeightedList.of()).forGetter(TrialSpawnerConfig::spawnPotentialsDefinition),
            WeightedList.codec(LootTable.KEY_CODEC)
               .optionalFieldOf("loot_tables_to_eject", DEFAULT.lootTablesToEject)
               .forGetter(TrialSpawnerConfig::lootTablesToEject),
            LootTable.KEY_CODEC
               .optionalFieldOf("items_to_drop_when_ominous", DEFAULT.itemsToDropWhenOminous)
               .forGetter(TrialSpawnerConfig::itemsToDropWhenOminous)
         )
         .apply(i, TrialSpawnerConfig::new)
   );
   public static final Codec<Holder<TrialSpawnerConfig>> CODEC = RegistryCodecs.holder(Registries.TRIAL_SPAWNER_CONFIG, DIRECT_CODEC);

   public int calculateTargetTotalMobs(final int additionalPlayers) {
      return (int)Math.floor(this.totalMobs + this.totalMobsAddedPerPlayer * additionalPlayers);
   }

   public int calculateTargetSimultaneousMobs(final int additionalPlayers) {
      return (int)Math.floor(this.simultaneousMobs + this.simultaneousMobsAddedPerPlayer * additionalPlayers);
   }

   public long ticksBetweenItemSpawners() {
      return 160L;
   }

   public static TrialSpawnerConfig.Builder builder() {
      return new TrialSpawnerConfig.Builder();
   }

   public TrialSpawnerConfig withSpawning(final EntityType<?> type) {
      CompoundTag tag = new CompoundTag();
      tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
      SpawnData spawnData = new SpawnData(tag, Optional.empty(), Optional.empty());
      return new TrialSpawnerConfig(
         this.spawnRange,
         this.totalMobs,
         this.simultaneousMobs,
         this.totalMobsAddedPerPlayer,
         this.simultaneousMobsAddedPerPlayer,
         this.ticksBetweenSpawn,
         WeightedList.of(spawnData),
         this.lootTablesToEject,
         this.itemsToDropWhenOminous
      );
   }

   public TrialSpawnerConfig withSpawning(final TypedEntityData<EntityType<?>> entityData) {
      CompoundTag tag = new CompoundTag();
      SpawnData spawnData = new SpawnData(tag, Optional.empty(), Optional.empty());
      entityData.loadInto(spawnData, BuiltInRegistries.ENTITY_TYPE);
      return new TrialSpawnerConfig(
         this.spawnRange,
         this.totalMobs,
         this.simultaneousMobs,
         this.totalMobsAddedPerPlayer,
         this.simultaneousMobsAddedPerPlayer,
         this.ticksBetweenSpawn,
         WeightedList.of(spawnData),
         this.lootTablesToEject,
         this.itemsToDropWhenOminous
      );
   }

   public static class Builder {
      private int spawnRange = 4;
      private float totalMobs = 6.0F;
      private float simultaneousMobs = 2.0F;
      private float totalMobsAddedPerPlayer = 2.0F;
      private float simultaneousMobsAddedPerPlayer = 1.0F;
      private int ticksBetweenSpawn = 40;
      private WeightedList<SpawnData> spawnPotentialsDefinition = WeightedList.of();
      private WeightedList<ResourceKey<LootTable>> lootTablesToEject = WeightedList.<ResourceKey<LootTable>>builder()
         .add(BuiltInLootTables.SPAWNER_TRIAL_CHAMBER_CONSUMABLES)
         .add(BuiltInLootTables.SPAWNER_TRIAL_CHAMBER_KEY)
         .build();
      private ResourceKey<LootTable> itemsToDropWhenOminous = BuiltInLootTables.SPAWNER_TRIAL_ITEMS_TO_DROP_WHEN_OMINOUS;

      public TrialSpawnerConfig.Builder spawnRange(final int spawnRange) {
         this.spawnRange = spawnRange;
         return this;
      }

      public TrialSpawnerConfig.Builder totalMobs(final float totalMobs) {
         this.totalMobs = totalMobs;
         return this;
      }

      public TrialSpawnerConfig.Builder simultaneousMobs(final float simultaneousMobs) {
         this.simultaneousMobs = simultaneousMobs;
         return this;
      }

      public TrialSpawnerConfig.Builder totalMobsAddedPerPlayer(final float totalMobsAddedPerPlayer) {
         this.totalMobsAddedPerPlayer = totalMobsAddedPerPlayer;
         return this;
      }

      public TrialSpawnerConfig.Builder simultaneousMobsAddedPerPlayer(final float simultaneousMobsAddedPerPlayer) {
         this.simultaneousMobsAddedPerPlayer = simultaneousMobsAddedPerPlayer;
         return this;
      }

      public TrialSpawnerConfig.Builder ticksBetweenSpawn(final int ticksBetweenSpawn) {
         this.ticksBetweenSpawn = ticksBetweenSpawn;
         return this;
      }

      public TrialSpawnerConfig.Builder spawnPotentialsDefinition(final WeightedList<SpawnData> spawnPotentialsDefinition) {
         this.spawnPotentialsDefinition = spawnPotentialsDefinition;
         return this;
      }

      public TrialSpawnerConfig.Builder lootTablesToEject(final WeightedList<ResourceKey<LootTable>> lootTablesToEject) {
         this.lootTablesToEject = lootTablesToEject;
         return this;
      }

      public TrialSpawnerConfig.Builder itemsToDropWhenOminous(final ResourceKey<LootTable> itemsToDropWhenOminous) {
         this.itemsToDropWhenOminous = itemsToDropWhenOminous;
         return this;
      }

      public TrialSpawnerConfig build() {
         return new TrialSpawnerConfig(
            this.spawnRange,
            this.totalMobs,
            this.simultaneousMobs,
            this.totalMobsAddedPerPlayer,
            this.simultaneousMobsAddedPerPlayer,
            this.ticksBetweenSpawn,
            this.spawnPotentialsDefinition,
            this.lootTablesToEject,
            this.itemsToDropWhenOminous
         );
      }
   }
}
```

## 试炼完整生成方法

原始出处：[证据 JSON](evidence/957d122cb28eccff3195d6ea8a8f6e7ee5e96c7fe66930ee859b8c4a656106b0.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
public final class TrialSpawner {
   public Optional<UUID> spawnMob(final ServerLevel level, final BlockPos spawnerPos) {
      RandomSource random = level.getRandom();
      SpawnData nextSpawnData = this.data.getOrCreateNextSpawnData(this, level.getRandom());

      try (ScopedCollector reporter = new ScopedCollector(() -> "spawner@" + spawnerPos, LOGGER)) {
         ValueInput input = TagValueInput.create(reporter, level.registryAccess(), nextSpawnData.entityToSpawn());
         Optional<EntityType<?>> entityType = EntityType.by(input);
         if (entityType.isEmpty()) {
            return Optional.empty();
         }

         Vec3 spawnPos = input.<Vec3>read("Pos", Vec3.CODEC)
            .orElseGet(
               () -> {
                  TrialSpawnerConfig activeConfig = this.activeConfig();
                  return new Vec3(
                     spawnerPos.getX() + (random.nextDouble() - random.nextDouble()) * activeConfig.spawnRange() + 0.5,
                     spawnerPos.getY() + random.nextInt(3) - 1,
                     spawnerPos.getZ() + (random.nextDouble() - random.nextDouble()) * activeConfig.spawnRange() + 0.5
                  );
               }
            );
         if (!level.noCollision(entityType.get().getSpawnAABB(spawnPos.x, spawnPos.y, spawnPos.z))) {
            return Optional.empty();
         }

         if (!inLineOfSight(level, Vec3.atCenterOf(spawnerPos), spawnPos)) {
            return Optional.empty();
         }

         BlockPos spawnBlockPos = BlockPos.containing(spawnPos);
         if (!SpawnPlacements.checkSpawnRules(entityType.get(), level, EntitySpawnReason.TRIAL_SPAWNER, spawnBlockPos, level.getRandom())) {
            return Optional.empty();
         }

         if (nextSpawnData.getCustomSpawnRules().isPresent()) {
            CustomSpawnRules customSpawnRules = nextSpawnData.getCustomSpawnRules().get();
            if (!customSpawnRules.isValidPosition(spawnBlockPos, level)) {
               return Optional.empty();
            }
         }

         Entity entity = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.TRIAL_SPAWNER, e -> {
            e.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, random.nextFloat() * 360.0F, 0.0F);
            return e;
         });
         if (entity == null) {
            return Optional.empty();
         }

         if (entity instanceof Mob mob) {
            if (!mob.checkSpawnObstruction(level)) {
               return Optional.empty();
            }

            boolean hasNoConfiguration = nextSpawnData.getEntityToSpawn().size() == 1 && nextSpawnData.getEntityToSpawn().getString("id").isPresent();
            if (hasNoConfiguration) {
               mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.TRIAL_SPAWNER, null);
            }

            mob.setPersistenceRequired();
            nextSpawnData.getEquipment().ifPresent(mob::equip);
         }

         if (!level.tryAddFreshEntityWithPassengers(entity)) {
            return Optional.empty();
         }

         TrialSpawner.FlameParticle flameParticle = this.isOminous ? TrialSpawner.FlameParticle.OMINOUS : TrialSpawner.FlameParticle.NORMAL;
         level.levelEvent(3011, spawnerPos, flameParticle.encode());
         level.levelEvent(3012, spawnBlockPos, flameParticle.encode());
         level.gameEvent(entity, GameEvent.ENTITY_PLACE, spawnBlockPos);
         return Optional.of(entity.getUUID());
      }
   }
}
```

## 试炼 tick 与移除追踪后的延迟

原始出处：[证据 JSON](evidence/d8e447b67882f0c2c98e98e8cb793566ad13df5a50d097a4150e8f52d32dc319.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
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
```

## 试炼生成总开关

原始出处：[证据 JSON](evidence/8dfc57a317f66725058543d746503d9fea4611b073fe504719a1c7945f766cfd.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
public final class TrialSpawner {
   public boolean canSpawnInLevel(final ServerLevel level) {
      if (!level.getGameRules().get(GameRules.SPAWNER_BLOCKS_WORK)) {
         return false;
      } else if (this.overridePeacefulAndMobSpawnRule) {
         return true;
      } else {
         return level.getDifficulty() == Difficulty.PEACEFUL ? false : level.getGameRules().get(GameRules.SPAWN_MOBS);
      }
   }
}
```

## 试炼激活距离与冷却配置

原始出处：[证据 JSON](evidence/08d18820dfa16fab8efb36ba237b383aa22b5de31ccd913a6f20d6fdfd8ef9b9.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
                  .optionalFieldOf("ominous_config", Holder.direct(TrialSpawnerConfig.DEFAULT))
                  .forGetter(TrialSpawner.FullConfig::ominous),
               ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("target_cooldown_length", 36000).forGetter(TrialSpawner.FullConfig::targetCooldownLength),
               Codec.intRange(1, 128).optionalFieldOf("required_player_range", 14).forGetter(TrialSpawner.FullConfig::requiredPlayerRange)
            )
            .apply(i, TrialSpawner.FullConfig::new)
      );
      public static final TrialSpawner.FullConfig DEFAULT = new TrialSpawner.FullConfig(
         Holder.direct(TrialSpawnerConfig.DEFAULT), Holder.direct(TrialSpawnerConfig.DEFAULT), 36000, 14
      );

      public TrialSpawner.FullConfig overrideEntity(final EntityType<?> type) {
         return new TrialSpawner.FullConfig(
            Holder.direct(this.normal.value().withSpawning(type)),
            Holder.direct(this.ominous.value().withSpawning(type)),
            this.targetCooldownLength,
            this.requiredPlayerRange
         );
      }

      public TrialSpawner.FullConfig overrideEntityData(final TypedEntityData<EntityType<?>> entityData) {
         return new TrialSpawner.FullConfig(
            Holder.direct(this.normal.value().withSpawning(entityData)),
            Holder.direct(this.ominous.value().withSpawning(entityData)),
            this.targetCooldownLength,
            this.requiredPlayerRange
         );
      }
   }

   public interface StateAccessor {
      void setState(Level level, TrialSpawnerState state);

      TrialSpawnerState getState();

      void markUpdated();
   }
}
```

## 试炼玩家检测与视线

原始出处：[证据 JSON](evidence/0a0663778286e6e4ed41d75c2ad457cf3062af74bd0a7ea982da60835e6f9be1.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
package net.minecraft.world.level.block.entity.trialspawner;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.shapes.CollisionContext;

public interface PlayerDetector {
   PlayerDetector NO_CREATIVE_PLAYERS = (level, selector, pos, requiredPlayerRange, requireLineOfSight) -> selector.getPlayers(
         level, p -> p.blockPosition().closerThan(pos, requiredPlayerRange) && !p.isCreative() && !p.isSpectator()
      )
      .stream()
      .filter(player -> !requireLineOfSight || inLineOfSight(level, Vec3.atCenterOf(pos), player.getEyePosition()))
      .map(Entity::getUUID)
      .toList();
   PlayerDetector INCLUDING_CREATIVE_PLAYERS = (level, selector, pos, requiredPlayerRange, requireLineOfSight) -> selector.getPlayers(
         level, p -> p.blockPosition().closerThan(pos, requiredPlayerRange) && !p.isSpectator()
      )
      .stream()
      .filter(player -> !requireLineOfSight || inLineOfSight(level, Vec3.atCenterOf(pos), player.getEyePosition()))
      .map(Entity::getUUID)
      .toList();
   PlayerDetector SHEEP = (level, selector, pos, requiredPlayerRange, requireLineOfSight) -> {
      AABB area = new AABB(pos).inflate(requiredPlayerRange);
      return selector.getEntities(level, EntityTypes.SHEEP, area, LivingEntity::isAlive)
         .stream()
         .filter(entity -> !requireLineOfSight || inLineOfSight(level, Vec3.atCenterOf(pos), entity.getEyePosition()))
         .map(Entity::getUUID)
         .toList();
   };

   List<UUID> detect(
      final ServerLevel level,
      final PlayerDetector.EntitySelector selector,
      final BlockPos spawnerPos,
      final double requiredPlayerRange,
      final boolean requireLineOfSight
   );

   private static boolean inLineOfSight(final Level level, final Vec3 origin, final Vec3 dest) {
      BlockHitResult hitResult = level.clip(new ClipContext(dest, origin, Block.VISUAL, Fluid.NONE, CollisionContext.empty()));
      return hitResult.getBlockPos().equals(BlockPos.containing(origin)) || hitResult.getType() == Type.MISS;
   }

   interface EntitySelector {
      PlayerDetector.EntitySelector SELECT_FROM_LEVEL = new PlayerDetector.EntitySelector() {
         @Override
         public List<ServerPlayer> getPlayers(final ServerLevel level, final Predicate<? super Player> selector) {
            return level.getPlayers(selector);
         }

         @Override
         public <T extends Entity> List<T> getEntities(
            final ServerLevel level, final EntityTypeTest<Entity, T> type, final AABB aabb, final Predicate<? super T> selector
         ) {
            return level.getEntities(type, aabb, selector);
         }
      };

      List<? extends Player> getPlayers(final ServerLevel level, final Predicate<? super Player> selector);

      <T extends Entity> List<T> getEntities(final ServerLevel level, final EntityTypeTest<Entity, T> type, final AABB bb, final Predicate<? super T> selector);

      static PlayerDetector.EntitySelector onlySelectPlayer(final Player player) {
         return onlySelectPlayers(List.of(player));
      }

      static PlayerDetector.EntitySelector onlySelectPlayers(final List<Player> players) {
         return new PlayerDetector.EntitySelector() {
            @Override
            public List<Player> getPlayers(final ServerLevel level, final Predicate<? super Player> selector) {
               return players.stream().filter(selector).toList();
            }

            @Override
            public <T extends Entity> List<T> getEntities(
               final ServerLevel level, final EntityTypeTest<Entity, T> type, final AABB bb, final Predicate<? super T> selector
            ) {
               return players.stream().map(type::tryCast).filter(Objects::nonNull).filter(selector).toList();
            }
         };
      }
   }
}
```

## 试炼默认检测器

原始出处：[证据 JSON](evidence/a536fde67fc7ec54664cfd87013d367873e8f09f364b2934f21665c1d16b0ff8.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
public class TrialSpawnerBlockEntity extends BlockEntity implements StateAccessor, Spawner {
   private TrialSpawner createDefaultSpawner() {
      PlayerDetector playerDetector = SharedConstants.DEBUG_TRIAL_SPAWNER_DETECTS_SHEEP_AS_PLAYERS ? PlayerDetector.SHEEP : PlayerDetector.NO_CREATIVE_PLAYERS;
      EntitySelector entitySelector = EntitySelector.SELECT_FROM_LEVEL;
      return new TrialSpawner(FullConfig.DEFAULT, this, playerDetector, entitySelector);
   }
}
```

## 试炼解除怪物追踪条件

原始出处：[证据 JSON](evidence/2c500ae60202301780ab999c1d72dcffccf229ef12848493478a6c56437178a5.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
public final class TrialSpawner {
   private static boolean shouldMobBeUntracked(final ServerLevel serverLevel, final BlockPos spawnerPos, final UUID id) {
      Entity entity = serverLevel.getEntity(id);
      return entity == null
         || !entity.isAlive()
         || !entity.level().dimension().equals(serverLevel.dimension())
         || entity.blockPosition().distSqr(spawnerPos) > MAX_MOB_TRACKING_DISTANCE_SQR;
   }
}
```

## 原版密室僵尸配置 JSON

原始出处：[证据 JSON](evidence/b37d4c9646905f01e4d2f3743765a660fc27ee2a2b52f7d1f13fba26c85f1ae4.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
{
  "simultaneous_mobs": 3.0,
  "simultaneous_mobs_added_per_player": 0.5,
  "spawn_potentials": [
    {
      "data": {
        "entity": {
          "id": "minecraft:zombie"
        }
      },
      "weight": 1
    }
  ],
  "ticks_between_spawn": 20
}
```

## 原版密室慢速远程配置 JSON

原始出处：[证据 JSON](evidence/18d882a9434cdd9248548670ff37f10ce949c6dec0065310f94e37e2e4cf7923.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
{
  "simultaneous_mobs": 4.0,
  "simultaneous_mobs_added_per_player": 2.0,
  "spawn_potentials": [
    {
      "data": {
        "entity": {
          "id": "minecraft:skeleton"
        }
      },
      "weight": 1
    }
  ],
  "ticks_between_spawn": 160
}
```

## 原版密室旋风人配置 JSON

原始出处：[证据 JSON](evidence/8b5a3238525ec618230aab61648d75865ac48a08e7677a142f0dba5adc885249.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
{
  "simultaneous_mobs": 1.0,
  "simultaneous_mobs_added_per_player": 0.5,
  "spawn_potentials": [
    {
      "data": {
        "entity": {
          "id": "minecraft:breeze"
        }
      },
      "weight": 1
    }
  ],
  "ticks_between_spawn": 20,
  "total_mobs": 2.0,
  "total_mobs_added_per_player": 1.0
}
```

## 方块实体 tick 调用条件

原始出处：[证据 JSON](evidence/7d00662beb5a92e0374af4f3453e56bdf1049ea421464f8c2804c1d87255e81c.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
public class LevelChunk extends ChunkAccess implements DebugValueSource {
   private class BoundTickingBlockEntity<T extends BlockEntity> implements TickingBlockEntity {
      @Override
      public void tick() {
         if (!this.blockEntity.isRemoved() && this.blockEntity.hasLevel()) {
            BlockPos pos = this.blockEntity.getBlockPos();
            if (LevelChunk.this.isTicking(pos)) {
               try {
                  ProfilerFiller profiler = Profiler.get();
                  profiler.push(this::getType);
                  BlockState blockState = LevelChunk.this.getBlockState(pos);
                  if (this.blockEntity.getType().isValid(blockState)) {
                     this.ticker.tick(LevelChunk.this.level, this.blockEntity.getBlockPos(), blockState, this.blockEntity);
                     this.loggedInvalidBlockState = false;
                  } else if (!this.loggedInvalidBlockState) {
                     this.loggedInvalidBlockState = true;
                     LevelChunk.LOGGER
                        .warn(
                           "Block entity {} @ {} state {} invalid for ticking:",
                           new Object[]{LogUtils.defer(this::getType), LogUtils.defer(this::getPos), blockState}
                        );
                  }

                  profiler.pop();
               } catch (Throwable t) {
                  CrashReport report = CrashReport.forThrowable(t, "Ticking block entity");
                  CrashReportCategory category = report.addCategory("Block entity being ticked");
                  this.blockEntity.fillCrashReportCategory(category);
                  throw new ReportedException(report);
               }
            }
         }
      }
   }
}
```

## 区块是否进行方块 tick

原始出处：[证据 JSON](evidence/5914de3fd28a11319d30d7413df5d3cfe263af1ee1d78e3a703507125ff8c16a.json)

```json
{"edition": "Java", "integrity": "matches server bundle SHA-256", "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar", "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6", "kind": "vanilla-server", "minecraft_version": "26.3"}
```

```
public class LevelChunk extends ChunkAccess implements DebugValueSource {
   private boolean isTicking(final BlockPos pos) {
      if (!this.level.getWorldBorder().isWithinBounds(pos)) {
         return false;
      } else {
         return !(this.level instanceof ServerLevel serverLevel)
            ? true
            : this.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING) && serverLevel.areEntitiesLoaded(ChunkPos.pack(pos));
      }
   }
}
```

