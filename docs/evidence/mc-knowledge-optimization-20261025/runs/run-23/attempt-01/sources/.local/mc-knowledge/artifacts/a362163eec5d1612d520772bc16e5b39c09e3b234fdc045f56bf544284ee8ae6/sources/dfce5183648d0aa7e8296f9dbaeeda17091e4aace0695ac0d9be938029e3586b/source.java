package net.minecraft.world.level.block.entity.trialspawner;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.ProblemReporter.ScopedCollector;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.SpawnData.CustomSpawnRules;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

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
