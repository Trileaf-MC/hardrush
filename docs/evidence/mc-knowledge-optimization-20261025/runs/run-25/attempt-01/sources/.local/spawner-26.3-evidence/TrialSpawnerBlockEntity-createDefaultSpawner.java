package net.minecraft.world.level.block.entity;

import net.minecraft.SharedConstants;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Spawner;
import net.minecraft.world.level.block.entity.trialspawner.PlayerDetector;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.trialspawner.PlayerDetector.EntitySelector;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner.FullConfig;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner.StateAccessor;

public class TrialSpawnerBlockEntity extends BlockEntity implements StateAccessor, Spawner {
   private TrialSpawner createDefaultSpawner() {
      PlayerDetector playerDetector = SharedConstants.DEBUG_TRIAL_SPAWNER_DETECTS_SHEEP_AS_PLAYERS ? PlayerDetector.SHEEP : PlayerDetector.NO_CREATIVE_PLAYERS;
      EntitySelector entitySelector = EntitySelector.SELECT_FROM_LEVEL;
      return new TrialSpawner(FullConfig.DEFAULT, this, playerDetector, entitySelector);
   }
}
