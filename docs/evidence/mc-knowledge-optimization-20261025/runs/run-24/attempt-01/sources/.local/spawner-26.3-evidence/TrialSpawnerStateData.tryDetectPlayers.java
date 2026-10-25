package net.minecraft.world.level.block.entity.trialspawner;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.random.WeightedList.Builder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.storage.loot.LootTable;

public class TrialSpawnerStateData {
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
}
