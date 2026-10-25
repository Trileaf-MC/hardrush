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
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.stats.Stats;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams.Builder;
import net.minecraft.world.waypoints.WaypointTransmitter;

public abstract class LivingEntity extends Entity implements Attackable, WaypointTransmitter {
   @Override
   public boolean hurtServer(final ServerLevel level, final DamageSource source, float damage) {
      if (this.isInvulnerableTo(level, source)) {
         return false;
      }

      if (this.isDeadOrDying()) {
         return false;
      }

      if (source.is(DamageTypeTags.IS_FIRE) && this.hasEffect(MobEffects.FIRE_RESISTANCE)) {
         return false;
      }

      if (this.isSleeping()) {
         this.stopSleeping();
      }

      this.noActionTime = 0;
      if (damage < 0.0F) {
         damage = 0.0F;
      }

      float originalDamage = damage;
      ItemStack itemInUse = this.getUseItem();
      float damageBlocked = this.applyItemBlocking(level, source, damage);
      damage -= damageBlocked;
      boolean blocked = damageBlocked > 0.0F;
      if (source.is(DamageTypeTags.IS_FREEZING) && this.is(EntityTypeTags.FREEZE_HURTS_EXTRA_TYPES)) {
         damage *= 5.0F;
      }

      if (source.is(DamageTypeTags.DAMAGES_HELMET) && !this.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
         this.hurtHelmet(source, damage);
         damage *= 0.75F;
      }

      if (Float.isNaN(damage) || Float.isInfinite(damage)) {
         damage = Float.MAX_VALUE;
      }

      boolean tookFullDamage = true;
      if (this.damageCooldownTime > 10.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
         if (damage <= this.lastHurt) {
            return false;
         }

         this.actuallyHurt(level, source, damage - this.lastHurt);
         this.lastHurt = damage;
         tookFullDamage = false;
      } else {
         this.lastHurt = damage;
         this.damageCooldownTime = 20;
         this.actuallyHurt(level, source, damage);
         this.hurtDuration = 10;
         this.hurtTime = this.hurtDuration;
      }

      this.resolveMobResponsibleForDamage(source);
      this.resolvePlayerResponsibleForDamage(source);
      if (tookFullDamage) {
         BlocksAttacks blocksAttacks = itemInUse.get(DataComponents.BLOCKS_ATTACKS);
         if (blocked && blocksAttacks != null) {
            blocksAttacks.onBlocked(level, this);
         } else {
            level.broadcastDamageEvent(this, source);
         }

         if (!source.is(DamageTypeTags.NO_IMPACT) && (!blocked || damage > 0.0F)) {
            this.markHurt();
         }

         boolean fullyBlocked = blocked && damage <= 0.0F;
         if (!source.is(DamageTypeTags.NO_KNOCKBACK) && !fullyBlocked) {
            this.dealDefaultKnockback(source, damage, blocked);
         }
      }

      if (this.isDeadOrDying()) {
         if (!this.checkTotemDeathProtection(source)) {
            if (tookFullDamage) {
               this.makeSound(this.getDeathSound());
               this.playSecondaryHurtSound(source);
            }

            this.die(source);
         }
      } else if (tookFullDamage) {
         this.playHurtSound(source);
         this.playSecondaryHurtSound(source);
      }

      boolean success = !blocked || damage > 0.0F;
      if (success) {
         this.lastDamageSource = source;
         this.lastDamageStamp = this.level().getGameTime();

         for (MobEffectInstance effect : this.getActiveEffects()) {
            effect.onMobHurt(level, this, source, damage);
         }
      }

      if (this instanceof ServerPlayer serverPlayer) {
         CriteriaTriggers.ENTITY_HURT_PLAYER.trigger(serverPlayer, source, originalDamage, damage, blocked);
         if (damageBlocked > 0.0F && damageBlocked < 3.4028235E37F) {
            serverPlayer.awardStat(Stats.DAMAGE_BLOCKED_BY_SHIELD, Math.round(damageBlocked * 10.0F));
         }
      }

      if (source.getEntity() instanceof ServerPlayer sourcePlayer) {
         CriteriaTriggers.PLAYER_HURT_ENTITY.trigger(sourcePlayer, this, source, originalDamage, damage, blocked);
      }

      return success;
   }
}
