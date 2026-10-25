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
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams.Builder;
import net.minecraft.world.waypoints.WaypointTransmitter;

public abstract class LivingEntity extends Entity implements Attackable, WaypointTransmitter {
   protected void actuallyHurt(final ServerLevel level, final DamageSource source, float dmg) {
      if (!this.isInvulnerableTo(level, source)) {
         dmg = this.getDamageAfterArmorAbsorb(source, dmg);
         dmg = this.getDamageAfterMagicAbsorb(source, dmg);
         float originalDamage = dmg;
         dmg = Math.max(dmg - this.getAbsorptionAmount(), 0.0F);
         this.setAbsorptionAmount(this.getAbsorptionAmount() - (originalDamage - dmg));
         float absorbedDamage = originalDamage - dmg;
         if (absorbedDamage > 0.0F && absorbedDamage < 3.4028235E37F && source.getEntity() instanceof ServerPlayer serverPlayer) {
            serverPlayer.awardStat(Stats.DAMAGE_DEALT_ABSORBED, Math.round(absorbedDamage * 10.0F));
         }

         if (dmg != 0.0F) {
            this.getCombatTracker().recordDamage(source, dmg);
            this.setHealth(this.getHealth() - dmg);
            this.setAbsorptionAmount(this.getAbsorptionAmount() - dmg);
            this.gameEvent(GameEvent.ENTITY_DAMAGE);
         }
      }
   }
}
