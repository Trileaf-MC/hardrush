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
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.parrot.Parrot.Variant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.gameevent.GameEvent;

public abstract class Player extends Avatar implements ContainerUser {
   @Override
   protected void actuallyHurt(final ServerLevel level, final DamageSource source, float dmg) {
      if (!this.isInvulnerableTo(level, source)) {
         dmg = this.getDamageAfterArmorAbsorb(source, dmg);
         dmg = this.getDamageAfterMagicAbsorb(source, dmg);
         float originalDamage = dmg;
         dmg = Math.max(dmg - this.getAbsorptionAmount(), 0.0F);
         this.setAbsorptionAmount(this.getAbsorptionAmount() - (originalDamage - dmg));
         float absorbedDamage = originalDamage - dmg;
         if (absorbedDamage > 0.0F && absorbedDamage < 3.4028235E37F) {
            this.awardStat(Stats.DAMAGE_ABSORBED, Math.round(absorbedDamage * 10.0F));
         }

         if (dmg != 0.0F) {
            this.causeFoodExhaustion(source.getFoodExhaustion());
            this.getCombatTracker().recordDamage(source, dmg);
            this.setHealth(this.getHealth() - dmg);
            if (dmg < 3.4028235E37F) {
               this.awardStat(Stats.DAMAGE_TAKEN, Math.round(dmg * 10.0F));
            }

            this.gameEvent(GameEvent.ENTITY_DAMAGE);
         }
      }
   }
}
