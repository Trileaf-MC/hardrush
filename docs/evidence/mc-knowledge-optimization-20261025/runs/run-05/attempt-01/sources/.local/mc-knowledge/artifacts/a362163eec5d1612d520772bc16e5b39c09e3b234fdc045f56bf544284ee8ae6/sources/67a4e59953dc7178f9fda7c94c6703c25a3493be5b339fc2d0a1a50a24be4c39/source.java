package net.minecraft.world.entity.animal.golem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public class IronGolem extends AbstractGolem implements NeutralMob {
   @Override
   public boolean doHurtTarget(final ServerLevel level, final Entity target) {
      this.attackAnimationTick = 10;
      level.broadcastEntityEvent(this, (byte)4);
      float attackDamage = this.getAttackDamage();
      float damage = (int)attackDamage > 0 ? attackDamage / 2.0F + this.random.nextInt((int)attackDamage) : attackDamage;
      DamageSource damageSource = this.damageSources().mobAttack(this);
      boolean hurt = target.hurtServer(level, damageSource, damage);
      if (hurt) {
         double knockbackResistance = target instanceof LivingEntity livingEntity ? livingEntity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) : 0.0;
         double scale = Math.max(0.0, 1.0 - knockbackResistance);
         target.setDeltaMovement(target.getDeltaMovement().add(0.0, 0.4F * scale, 0.0));
         EnchantmentHelper.doPostAttackEffects(level, target, damageSource);
      }

      this.playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.0F, 1.0F);
      return hurt;
   }
}
