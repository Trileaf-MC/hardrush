package net.minecraft.world.level;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.ProblemReporter.ScopedCollector;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.SpawnData.CustomSpawnRules;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
