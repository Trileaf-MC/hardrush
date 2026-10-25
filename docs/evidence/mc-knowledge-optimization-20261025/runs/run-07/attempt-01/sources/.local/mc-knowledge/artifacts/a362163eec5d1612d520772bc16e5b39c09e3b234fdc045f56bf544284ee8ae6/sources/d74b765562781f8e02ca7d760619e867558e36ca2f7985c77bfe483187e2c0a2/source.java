package net.minecraft.world.entity;

import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;

public class ConversionTracker<M extends Mob> {
   public void tick() {
      if (this.mob.level() instanceof ServerLevel serverLevel && this.mob.isAlive() && !this.mob.isNoAi()) {
         if (this.isAfflicted.getAsBoolean()) {
            if (this.isConverting()) {
               this.conversionTime--;
               if (this.conversionTime < 0) {
                  this.doConversion(serverLevel);
               }
            } else {
               this.afflictionTime++;
               if (this.afflictionTime >= this.totalAfflictionTime) {
                  this.startConversion(this.totalConversionTime);
               }
            }
         } else {
            this.afflictionTime = -1;
            this.setConverting(false);
         }
      }
   }
}
