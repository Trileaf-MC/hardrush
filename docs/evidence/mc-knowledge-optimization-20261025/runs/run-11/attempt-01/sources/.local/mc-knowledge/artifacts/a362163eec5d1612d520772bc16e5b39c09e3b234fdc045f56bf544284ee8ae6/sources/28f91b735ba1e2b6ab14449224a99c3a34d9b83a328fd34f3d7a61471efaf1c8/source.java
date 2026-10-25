package net.minecraft.world.entity.player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stat;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.parrot.Parrot.Variant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

public abstract class Player extends Avatar implements ContainerUser {
   @Override
   public boolean hurtServer(final ServerLevel level, final DamageSource source, float damage) {
      if (this.isInvulnerableTo(level, source)) {
         return false;
      }

      if (this.abilities.invulnerable && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
         return false;
      }

      this.noActionTime = 0;
      if (this.isDeadOrDying()) {
         return false;
      }

      this.removeEntitiesOnShoulder();
      if (source.scalesWithDifficulty()) {
         if (level.getDifficulty() == Difficulty.PEACEFUL) {
            damage = 0.0F;
         }

         if (level.getDifficulty() == Difficulty.EASY) {
            damage = Math.min(damage / 2.0F + 1.0F, damage);
         }

         if (level.getDifficulty() == Difficulty.HARD) {
            damage = damage * 3.0F / 2.0F;
         }
      }

      return damage == 0.0F ? false : super.hurtServer(level, source, damage);
   }
}
