package net.minecraft.world.level.block;

import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

public class PowderSnowBlock extends Block implements BucketPickup {
   public static boolean canEntityWalkOnPowderSnow(final Entity entity) {
      if (entity.is(EntityTypeTags.POWDER_SNOW_WALKABLE_MOBS)) {
         return true;
      } else {
         return entity instanceof LivingEntity livingEntity ? livingEntity.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS) : false;
      }
   }
}
