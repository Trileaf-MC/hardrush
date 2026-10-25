package net.minecraft.world.level.block.entity.trialspawner;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.loot.LootTable;

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
