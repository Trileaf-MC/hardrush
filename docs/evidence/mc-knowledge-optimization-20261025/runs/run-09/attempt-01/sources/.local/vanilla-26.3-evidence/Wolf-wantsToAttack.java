package net.minecraft.world.entity.animal.wolf;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.player.Player;

public class Wolf extends TamableAnimal implements NeutralMob {
   @Override
   public boolean wantsToAttack(final LivingEntity target, final LivingEntity owner) {
      if (target instanceof Creeper || target instanceof Ghast || target instanceof ArmorStand) {
         return false;
      } else if (target instanceof Wolf wolfTarget) {
         return !wolfTarget.isTame() || wolfTarget.getOwner() != owner;
      } else if (target instanceof Player playerTarget && owner instanceof Player playerOwner && !playerOwner.canHarmPlayer(playerTarget)) {
         return false;
      } else {
         return target instanceof AbstractHorse horse && horse.isTamed() ? false : !(target instanceof TamableAnimal animal && animal.isTame());
      }
   }
}
