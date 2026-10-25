package net.minecraft.world.damagesource;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;

public class DamageSources {
   public DamageSource mobAttack(final LivingEntity mob) {
      return this.source(DamageTypes.MOB_ATTACK, mob);
   }
}
