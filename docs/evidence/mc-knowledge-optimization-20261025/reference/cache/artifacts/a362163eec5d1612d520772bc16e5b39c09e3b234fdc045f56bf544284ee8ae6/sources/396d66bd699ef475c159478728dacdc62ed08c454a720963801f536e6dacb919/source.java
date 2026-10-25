package net.minecraft.world.entity;

import com.mojang.datafixers.util.Pair;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.stats.Stats;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams.Builder;
import net.minecraft.world.waypoints.WaypointTransmitter;

public abstract class LivingEntity extends Entity implements Attackable, WaypointTransmitter {
   protected float getDamageAfterMagicAbsorb(final DamageSource damageSource, float damage) {
      if (damageSource.is(DamageTypeTags.BYPASSES_EFFECTS)) {
         return damage;
      }

      if (this.hasEffect(MobEffects.RESISTANCE) && !damageSource.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
         int absorbValue = (this.getEffect(MobEffects.RESISTANCE).getAmplifier() + 1) * 5;
         int absorb = 25 - absorbValue;
         float v = damage * absorb;
         float oldDamage = damage;
         damage = Math.max(v / 25.0F, 0.0F);
         float damageResisted = oldDamage - damage;
         if (damageResisted > 0.0F && damageResisted < 3.4028235E37F) {
            if (this instanceof ServerPlayer serverPlayer) {
               serverPlayer.awardStat(Stats.DAMAGE_RESISTED, Math.round(damageResisted * 10.0F));
            } else if (damageSource.getEntity() instanceof ServerPlayer) {
               ((ServerPlayer)damageSource.getEntity()).awardStat(Stats.DAMAGE_DEALT_RESISTED, Math.round(damageResisted * 10.0F));
            }
         }
      }

      if (damage <= 0.0F) {
         return 0.0F;
      }

      if (damageSource.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
         return damage;
      }

      float enchantmentArmor;
      if (this.level() instanceof ServerLevel serverLevel) {
         enchantmentArmor = EnchantmentHelper.getDamageProtection(serverLevel, this, damageSource);
      } else {
         enchantmentArmor = 0.0F;
      }

      if (enchantmentArmor > 0.0F) {
         damage = CombatRules.getDamageAfterMagicAbsorb(damage, enchantmentArmor);
      }

      return damage;
   }
}
