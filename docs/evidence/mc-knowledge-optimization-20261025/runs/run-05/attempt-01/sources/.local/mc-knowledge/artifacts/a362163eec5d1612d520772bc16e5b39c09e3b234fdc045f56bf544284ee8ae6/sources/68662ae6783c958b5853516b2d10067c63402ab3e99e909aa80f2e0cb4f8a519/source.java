package net.minecraft.world.damagesource;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public class CombatRules {
   public static float getDamageAfterAbsorb(
      final LivingEntity victim, final float damage, final DamageSource source, final float totalArmor, final float armorToughness
   ) {
      float toughness = 2.0F + armorToughness / 4.0F;
      float realArmor = Mth.clamp(totalArmor - damage / toughness, totalArmor * 0.2F, 20.0F);
      float armorFraction = realArmor / 25.0F;
      ItemStack weaponItem = source.getWeaponItem();
      float modifiedArmorFraction;
      if (weaponItem != null && victim.level() instanceof ServerLevel level) {
         modifiedArmorFraction = Mth.clamp(EnchantmentHelper.modifyArmorEffectiveness(level, weaponItem, victim, source, armorFraction), 0.0F, 1.0F);
      } else {
         modifiedArmorFraction = armorFraction;
      }

      float damageMultiplier = 1.0F - modifiedArmorFraction;
      return damage * damageMultiplier;
   }
}
