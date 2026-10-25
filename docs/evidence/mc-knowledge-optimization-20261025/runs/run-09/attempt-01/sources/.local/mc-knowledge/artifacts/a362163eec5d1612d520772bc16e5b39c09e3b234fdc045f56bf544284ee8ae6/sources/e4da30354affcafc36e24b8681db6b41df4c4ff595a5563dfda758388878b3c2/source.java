package net.minecraft.world.entity;

import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.debug.DebugGoalInfo.DebugGoal;
import net.minecraft.world.entity.ConversionParams.AfterConversion;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jspecify.annotations.Nullable;

public abstract class Mob extends LivingEntity implements Targeting, EquipmentUser, Leashable {
   public <T extends Mob> @Nullable T convertTo(
      final EntityType<T> entityType, final ConversionParams params, final EntitySpawnReason spawnReason, final AfterConversion<T> afterConversion
   ) {
      if (this.isRemoved()) {
         return null;
      }

      T newMob = (T)entityType.create(this.level(), spawnReason);
      if (newMob == null) {
         return null;
      }

      params.type().convert(this, newMob, params);
      afterConversion.finalizeConversion(newMob);
      if (this.level() instanceof ServerLevel serverLevel) {
         serverLevel.addFreshEntity(newMob);
      }

      if (params.type().shouldDiscardAfterConversion()) {
         this.discard();
      }

      return newMob;
   }
}
