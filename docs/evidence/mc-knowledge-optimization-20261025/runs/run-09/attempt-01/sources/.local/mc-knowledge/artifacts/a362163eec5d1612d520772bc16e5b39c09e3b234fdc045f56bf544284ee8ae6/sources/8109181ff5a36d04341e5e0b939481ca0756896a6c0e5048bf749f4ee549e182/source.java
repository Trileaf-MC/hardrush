package net.minecraft.world.entity;

import com.google.common.collect.ImmutableList.Builder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.TypedInstance;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.debug.DebugValueSource;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.ScoreHolder;

public abstract class Entity
   implements Nameable,
   EntityAccess,
   ScoreHolder,
   SyncedDataHolder,
   DataComponentGetter,
   ItemOwner,
   SlotProvider,
   DebugValueSource,
   TypedInstance<EntityType<?>> {
   public void baseTick() {
      ProfilerFiller profiler = Profiler.get();
      profiler.push("entityBaseTick");
      this.computeSpeed();
      this.inBlockState = null;
      if (this.isPassenger() && this.getVehicle().isRemoved()) {
         this.stopRiding();
      }

      if (this.boardingCooldown > 0) {
         this.boardingCooldown--;
      }

      this.handlePortal();
      if (this.canSpawnSprintParticle()) {
         this.spawnSprintParticle();
      }

      this.wasInPowderSnow = this.isInPowderSnow;
      this.isInPowderSnow = false;
      this.wasEyeInWater = this.isEyeInFluid(FluidTags.WATER);
      this.updateFluidInteraction();
      this.updateSwimming();
      if (this.level() instanceof ServerLevel serverLevel) {
         if (this.remainingFireTicks > 0) {
            if (this.fireImmune()) {
               this.clearFire();
            } else {
               if (this.remainingFireTicks % 20 == 0 && !this.isInLava()) {
                  this.hurtServer(serverLevel, this.damageSources().onFire(), 1.0F);
               }

               this.setRemainingFireTicks(this.remainingFireTicks - 1);
            }
         }
      } else {
         this.clearFire();
      }

      if (this.isInLava()) {
         this.fallDistance *= 0.5;
      }

      this.checkBelowWorld();
      if (!this.level().isClientSide()) {
         this.setSharedFlagOnFire(this.remainingFireTicks > 0);
      }

      this.firstTick = false;
      if (this.level() instanceof ServerLevel serverLevel && this instanceof Leashable) {
         Leashable.tickLeash(serverLevel, (Entity & Leashable)this);
      }

      profiler.pop();
   }
}
