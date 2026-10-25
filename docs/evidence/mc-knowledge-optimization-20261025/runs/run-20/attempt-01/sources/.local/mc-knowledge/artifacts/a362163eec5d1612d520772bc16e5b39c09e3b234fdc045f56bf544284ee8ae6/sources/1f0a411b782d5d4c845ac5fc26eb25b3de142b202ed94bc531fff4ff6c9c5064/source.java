package net.minecraft.world.level.block.entity.trialspawner;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.random.WeightedList.Builder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner.FlameParticle;
import net.minecraft.world.level.storage.loot.LootTable;

public class TrialSpawnerStateData {
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
}
