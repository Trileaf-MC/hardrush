# 本次调查的原版代码证据摘录

全部内容来自本次 mc-knowledge CLI 查询；未启动游戏客户端或服务器。
Java Edition 26.3；以下行号为本文件行号，代码为反编译视图／javap 字节码，并非 Mojang 原始源码行号。


## 版本与制品身份

原始证据：[JSON](mc-knowledge/evidence/28d2e98b3ee5916dbff46dacd47376cf52ef5e960877b755f540a02e5f08eaca.json)

```json
{
  "cache_dir": "/private/tmp/hardrush-mc-optimization-20261025/runs/run-16/attempt-01/workspace/.local/mc-knowledge",
  "class_count": 7744,
  "jdk": {
    "java": "/Users/leifwebber/projects/minecraft/hardrush/.local/toolchains/jdk-25.0.4.1+1/Contents/Home/bin/java",
    "javap": "/Users/leifwebber/projects/minecraft/hardrush/.local/toolchains/jdk-25.0.4.1+1/Contents/Home/bin/javap",
    "major": 25
  },
  "ok": true,
  "source": {
    "edition": "Java",
    "integrity": "matches server bundle SHA-256",
    "jar": "/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar",
    "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6",
    "kind": "vanilla-server",
    "minecraft_version": "26.3"
  },
  "vineflower": {
    "path": "/Users/leifwebber/.gradle/caches/modules-2/files-2.1/org.vineflower/vineflower/1.12.0/85570609a0a5941a7d2918b6260b209de810f66f/vineflower-1.12.0.jar",
    "sha256": "1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d",
    "version": "1.12.0"
  }
}
```

## Skeleton：转化参数、触发条件与颤抖

原始证据：[JSON](mc-knowledge/evidence/fd6056b72d9fcd6872b89b8a727cf6978d08ebc9b31c28b959a9145a9a25b11c.json)

```java
package net.minecraft.world.entity.monster.skeleton;

import com.google.common.annotations.VisibleForTesting;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.SynchedEntityData.Builder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ConversionTracker;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class Skeleton extends AbstractSkeleton {
   private static final EntityDataAccessor<Boolean> DATA_STRAY_CONVERSION_ID = SynchedEntityData.defineId(Skeleton.class, EntityDataSerializers.BOOLEAN);
   private final ConversionTracker<AbstractSkeleton> freezingTracker = new ConversionTracker<>(
      this, DATA_STRAY_CONVERSION_ID, () -> EntityTypes.STRAY, () -> 1048, () -> this.isInPowderSnow, "FreezingTime", 140, "StrayConversionTime", 300, null
   );

   public Skeleton(final EntityType<? extends Skeleton> type, final Level level) {
      super(type, level);
   }

   @Override
   protected void defineSynchedData(final Builder entityData) {
      super.defineSynchedData(entityData);
      entityData.define(DATA_STRAY_CONVERSION_ID, false);
   }

   @Override
   public boolean isShaking() {
      return this.freezingTracker.isConverting();
   }

   @Override
   public void tick() {
      this.freezingTracker.tick();
      super.tick();
   }

   @Override
   protected void addAdditionalSaveData(final ValueOutput output) {
      super.addAdditionalSaveData(output);
      this.freezingTracker.addAdditionalSaveData(output);
   }

   @Override
   protected void readAdditionalSaveData(final ValueInput input) {
      super.readAdditionalSaveData(input);
      this.freezingTracker.readAdditionalSaveData(input);
   }

   @VisibleForTesting
   public void startFreezeConversion(final int time) {
      this.freezingTracker.startConversion(time);
   }

   @Override
   public boolean canFreeze() {
      return false;
   }

   @Override
   protected SoundEvent getAmbientSound() {
      return SoundEvents.SKELETON_AMBIENT;
   }

   @Override
   protected SoundEvent getHurtSound(final DamageSource source) {
      return SoundEvents.SKELETON_HURT;
   }

   @Override
   protected SoundEvent getDeathSound() {
      return SoundEvents.SKELETON_DEATH;
   }

   @Override
   protected SoundEvent getStepSound() {
      return SoundEvents.SKELETON_STEP;
   }
}
```

## Skeleton 构造器字节码：140 与 300

原始证据：[JSON](mc-knowledge/evidence/5b21f8bfec43c1f5ca0c5d73b0f7adefc3e00364d0a30861273ce2be3ef687a1.json)

```text
  public net.minecraft.world.entity.monster.skeleton.Skeleton(net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.monster.skeleton.Skeleton>, net.minecraft.world.level.Level);
    descriptor: (Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V
    Code:
         0: aload_0
         1: aload_1
         2: aload_2
         3: invokespecial #1                  // Method net/minecraft/world/entity/monster/skeleton/AbstractSkeleton."<init>":(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V
         6: aload_0
         7: new           #7                  // class net/minecraft/world/entity/ConversionTracker
        10: dup
        11: aload_0
        12: getstatic     #9                  // Field DATA_STRAY_CONVERSION_ID:Lnet/minecraft/network/syncher/EntityDataAccessor;
        15: invokedynamic #15,  0             // InvokeDynamic #0:get:()Ljava/util/function/Supplier;
        20: invokedynamic #19,  0             // InvokeDynamic #1:get:()Ljava/util/function/Supplier;
        25: aload_0
        26: invokedynamic #20,  0             // InvokeDynamic #2:getAsBoolean:(Lnet/minecraft/world/entity/monster/skeleton/Skeleton;)Ljava/util/function/BooleanSupplier;
        31: ldc           #24                 // String FreezingTime
        33: sipush        140
        36: ldc           #28                 // String StrayConversionTime
        38: sipush        300
        41: aconst_null
        42: invokespecial #32                 // Method net/minecraft/world/entity/ConversionTracker."<init>":(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/util/function/Supplier;Ljava/util/function/Supplier;Ljava/util/function/BooleanSupplier;Ljava/lang/String;ILjava/lang/String;ILjava/util/function/BiConsumer;)V
        45: putfield      #35                 // Field freezingTracker:Lnet/minecraft/world/entity/ConversionTracker;
        48: return
```

## ConversionTracker：完整状态机

原始证据：[JSON](mc-knowledge/evidence/ef5c72f3485d29eb778722a606e2fb8af922a315e9a5934a8946d69746104eb9.json)

```java
package net.minecraft.world.entity;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LevelEvent.Value;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public class ConversionTracker<M extends Mob> {
   private static final int NOT_CONVERTING = -1;
   private static final int DEFAULT_AFFLICTION_TIME = 0;
   private final M mob;
   private final EntityDataAccessor<Boolean> conversionId;
   private final Supplier<EntityType<? extends M>> convertsTo;
   private final Supplier<@Value Integer> conversionSound;
   private final BooleanSupplier isAfflicted;
   private final String afflictionTimeTag;
   private final int totalAfflictionTime;
   private final String conversionTimeTag;
   private final int totalConversionTime;
   private final @Nullable BiConsumer<M, ServerLevel> postConversionStep;
   private int afflictionTime = 0;
   private int conversionTime;

   public ConversionTracker(
      final M mob,
      final EntityDataAccessor<Boolean> conversionId,
      final Supplier<EntityType<? extends M>> convertsTo,
      final Supplier<@Value Integer> conversionSound,
      final BooleanSupplier isAfflicted,
      final String afflictionTimeTag,
      final int totalAfflictionTime,
      final String conversionTimeTag,
      final int totalConversionTime,
      final @Nullable BiConsumer<M, ServerLevel> postConversionStep
   ) {
      this.mob = mob;
      this.conversionId = conversionId;
      this.convertsTo = convertsTo;
      this.conversionSound = conversionSound;
      this.isAfflicted = isAfflicted;
      this.afflictionTimeTag = afflictionTimeTag;
      this.totalAfflictionTime = totalAfflictionTime;
      this.conversionTimeTag = conversionTimeTag;
      this.totalConversionTime = totalConversionTime;
      this.postConversionStep = postConversionStep;
   }

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

   public boolean isConverting() {
      return this.mob.getEntityData().get(this.conversionId);
   }

   private void setConverting(final boolean isConverting) {
      this.mob.getEntityData().set(this.conversionId, isConverting);
   }

   public void addAdditionalSaveData(final ValueOutput output) {
      output.putInt(this.conversionTimeTag, this.isConverting() ? this.conversionTime : -1);
      output.putInt(this.afflictionTimeTag, this.isAfflicted.getAsBoolean() ? this.afflictionTime : -1);
   }

   public void readAdditionalSaveData(final ValueInput input) {
      this.afflictionTime = input.getIntOr(this.afflictionTimeTag, 0);
      int conversionTime = input.getIntOr(this.conversionTimeTag, -1);
      if (conversionTime != -1) {
         this.startConversion(conversionTime);
      } else {
         this.setConverting(false);
      }
   }

   private void doConversion(final ServerLevel serverLevel) {
      this.mob.convertTo(this.convertsTo.get(), ConversionParams.single(this.mob, true, true), converted -> {
         if (this.postConversionStep != null) {
            this.postConversionStep.accept((M)converted, serverLevel);
         }

         if (!converted.isSilent()) {
            serverLevel.levelEvent(null, this.conversionSound.get(), converted.blockPosition(), 0);
         }
      });
   }

   public void startConversion(final int time) {
      this.conversionTime = time;
      this.setConverting(true);
   }

   public void setAfflictionTime(final int afflictionTime) {
      this.afflictionTime = afflictionTime;
   }

   public void setConversionTime(final int conversionTime) {
      this.conversionTime = conversionTime;
   }
}
```

## ConversionTracker.tick 字节码复核

原始证据：[JSON](mc-knowledge/evidence/73d822335878d3ce35cf700d7e912f9c90c15dd5c3e8d078fe2e1b8bed233705.json)

```text
  public void tick();
    descriptor: ()V
    Code:
         0: aload_0
         1: getfield      #13                 // Field mob:Lnet/minecraft/world/entity/Mob;
         4: invokevirtual #49                 // Method net/minecraft/world/entity/Mob.level:()Lnet/minecraft/world/level/Level;
         7: astore_2
         8: aload_2
         9: instanceof    #55                 // class net/minecraft/server/level/ServerLevel
        12: ifeq          126
        15: aload_2
        16: checkcast     #55                 // class net/minecraft/server/level/ServerLevel
        19: astore_1
        20: aload_0
        21: getfield      #13                 // Field mob:Lnet/minecraft/world/entity/Mob;
        24: invokevirtual #57                 // Method net/minecraft/world/entity/Mob.isAlive:()Z
        27: ifeq          126
        30: aload_0
        31: getfield      #13                 // Field mob:Lnet/minecraft/world/entity/Mob;
        34: invokevirtual #61                 // Method net/minecraft/world/entity/Mob.isNoAi:()Z
        37: ifne          126
        40: aload_0
        41: getfield      #28                 // Field isAfflicted:Ljava/util/function/BooleanSupplier;
        44: invokeinterface #64,  1           // InterfaceMethod java/util/function/BooleanSupplier.getAsBoolean:()Z
        49: ifeq          116
        52: aload_0
        53: invokevirtual #69                 // Method isConverting:()Z
        56: ifeq          84
        59: aload_0
        60: dup
        61: getfield      #72                 // Field conversionTime:I
        64: iconst_1
        65: isub
        66: putfield      #72                 // Field conversionTime:I
        69: aload_0
        70: getfield      #72                 // Field conversionTime:I
        73: ifge          126
        76: aload_0
        77: aload_1
        78: invokevirtual #75                 // Method doConversion:(Lnet/minecraft/server/level/ServerLevel;)V
        81: goto          126
        84: aload_0
        85: dup
        86: getfield      #9                  // Field afflictionTime:I
        89: iconst_1
        90: iadd
        91: putfield      #9                  // Field afflictionTime:I
        94: aload_0
        95: getfield      #9                  // Field afflictionTime:I
        98: aload_0
        99: getfield      #36                 // Field totalAfflictionTime:I
       102: if_icmplt     126
       105: aload_0
       106: aload_0
       107: getfield      #42                 // Field totalConversionTime:I
       110: invokevirtual #79                 // Method startConversion:(I)V
       113: goto          126
       116: aload_0
       117: iconst_m1
       118: putfield      #9                  // Field afflictionTime:I
       121: aload_0
       122: iconst_0
       123: invokevirtual #83                 // Method setConverting:(Z)V
       126: return
```

## Wolf.registerGoals：索敌与行为优先级

原始证据：[JSON](mc-knowledge/evidence/809b61b72bb3ca62525fb01b20313e9ae665c9ccdae93a7de1aa5ba4502fcf93.json)

```java
public class Wolf extends TamableAnimal implements NeutralMob {
   @Override
   protected void registerGoals() {
      this.goalSelector.addGoal(1, new FloatGoal(this));
      this.goalSelector.addGoal(1, new TamableAnimalPanicGoal((double)this, 1.5, DamageTypeTags.PANIC_ENVIRONMENTAL_CAUSES));
      this.goalSelector.addGoal(2, new SitWhenOrderedToGoal(this));
      this.goalSelector.addGoal(3, new Wolf.WolfAvoidEntityGoal<>(this, Llama.class, 24.0F, 1.5, 1.5));
      this.goalSelector.addGoal(4, new LeapAtTargetGoal(this, 0.4F));
      this.goalSelector.addGoal(5, new MeleeAttackGoal(this, 1.0, true));
      this.goalSelector.addGoal(6, new FollowOwnerGoal(this, 1.0, 10.0F, 2.0F));
      this.goalSelector.addGoal(7, new BreedGoal(this, 1.0));
      this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0));
      this.goalSelector.addGoal(9, new BegGoal(this, 8.0F));
      this.goalSelector.addGoal(10, new LookAtPlayerGoal(this, Player.class, 8.0F));
      this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));
      this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));
      this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));
      this.targetSelector.addGoal(3, new HurtByTargetGoal(this).setAlertOthers());
      this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false, this::isAngryAt));
      this.targetSelector.addGoal(5, new NonTameRandomTargetGoal<>(this, Animal.class, false, PREY_SELECTOR));
      this.targetSelector.addGoal(6, new NonTameRandomTargetGoal<>(this, Turtle.class, false, Turtle.BABY_ON_LAND_SELECTOR));
      this.targetSelector.addGoal(7, new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false));
      this.targetSelector.addGoal(8, new ResetUniversalAngerTargetGoal<>(this, true));
   }
}
```

## Stray：继承 AbstractSkeleton

原始证据：[JSON](mc-knowledge/evidence/1747dca4ee3e4fe6e38e644788af80fcd9df21db18858633c34465690ba77a56.json)

```java
package net.minecraft.world.entity.monster.skeleton;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

public class Stray extends AbstractSkeleton {
   public Stray(final EntityType<? extends Stray> type, final Level level) {
      super(type, level);
   }

   public static boolean checkStraySpawnRules(
      final EntityType<Stray> type, final ServerLevelAccessor level, final EntitySpawnReason spawnReason, final BlockPos pos, final RandomSource random
   ) {
      BlockPos checkSkyPos = pos;

      do {
         checkSkyPos = checkSkyPos.above();
      } while (level.getBlockState(checkSkyPos).is(Blocks.POWDER_SNOW));

      return Monster.checkMonsterSpawnRules(type, level, spawnReason, pos, random)
         && (EntitySpawnReason.isSpawner(spawnReason) || level.canSeeSky(checkSkyPos.below()));
   }

   @Override
   protected SoundEvent getAmbientSound() {
      return SoundEvents.STRAY_AMBIENT;
   }

   @Override
   protected SoundEvent getHurtSound(final DamageSource source) {
      return SoundEvents.STRAY_HURT;
   }

   @Override
   protected SoundEvent getDeathSound() {
      return SoundEvents.STRAY_DEATH;
   }

   @Override
   protected SoundEvent getStepSound() {
      return SoundEvents.STRAY_STEP;
   }

   @Override
   protected AbstractArrow getArrow(final ItemStack projectile, final float power, final @Nullable ItemStack firingWeapon) {
      AbstractArrow arrow = super.getArrow(projectile, power, firingWeapon);
      if (arrow instanceof Arrow arrow2) {
         arrow2.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 600));
      }

      return arrow;
   }
}
```

## NonTameRandomTargetGoal：未驯服限制的位置

原始证据：[JSON](mc-knowledge/evidence/7a980147ec6c0a7aa019a20661e95e985709f0f1b5e7334c4d2e5bb395f1911c.json)

```java
package net.minecraft.world.entity.ai.goal.target;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions.Selector;
import org.jspecify.annotations.Nullable;

public class NonTameRandomTargetGoal<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {
   private final TamableAnimal tamableMob;

   public NonTameRandomTargetGoal(final TamableAnimal mob, final Class<T> targetType, final boolean mustSee, final @Nullable Selector subselector) {
      super(mob, targetType, 10, mustSee, false, subselector);
      this.tamableMob = mob;
   }

   @Override
   public boolean canUse() {
      return !this.tamableMob.isTame() && super.canUse();
   }

   @Override
   public boolean canContinueToUse() {
      return this.targetConditions != null ? this.targetConditions.test(getServerLevel(this.mob), this.mob, this.target) : super.canContinueToUse();
   }
}
```

## SitWhenOrderedToGoal：坐下条件和行为

原始证据：[JSON](mc-knowledge/evidence/01d4432d42700080bd28b5bf6cda45941c5f95c8d8412f9c72dee98ae6bbfd5c.json)

```java
package net.minecraft.world.entity.ai.goal;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal.Flag;

public class SitWhenOrderedToGoal extends Goal {
   private final TamableAnimal mob;

   public SitWhenOrderedToGoal(final TamableAnimal mob) {
      this.mob = mob;
      this.setFlags(EnumSet.of(Flag.JUMP, Flag.MOVE));
   }

   @Override
   public boolean canContinueToUse() {
      return this.mob.isOrderedToSit();
   }

   @Override
   public boolean canUse() {
      boolean orderedToSit = this.mob.isOrderedToSit();
      if (!orderedToSit && !this.mob.isTame()) {
         return false;
      } else if (this.mob.isInWater()) {
         return false;
      } else if (!this.mob.onGround()) {
         return false;
      } else {
         LivingEntity owner = this.mob.getOwner();
         if (owner == null || owner.level() != this.mob.level()) {
            return true;
         } else {
            return this.mob.distanceToSqr(owner) < 144.0 && owner.getLastHurtByMob() != null ? false : orderedToSit;
         }
      }
   }

   @Override
   public void start() {
      this.mob.getNavigation().stop();
      this.mob.setInSittingPose(true);
   }

   @Override
   public void stop() {
      this.mob.setInSittingPose(false);
   }
}
```

## Wolf.hurtServer：受伤解除坐下命令

原始证据：[JSON](mc-knowledge/evidence/69c07a55f039abbed678bf49b026ca70c1ea93b6e7ee8d43dce3243d5eaa47ea.json)

```java
public class Wolf extends TamableAnimal implements NeutralMob {
   @Override
   public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
      if (this.isInvulnerableTo(level, source)) {
         return false;
      }

      this.setOrderedToSit(false);
      return super.hurtServer(level, source, damage);
   }
}
```

## InsideBlockEffectType：细雪标志与冻结免疫的关系

原始证据：[JSON](mc-knowledge/evidence/04acf87731cb2d7f0efe0d2a6ef6079f0127c65e44fba98c1914a1cbf5e2b475.json)

```java
package net.minecraft.world.entity;

import java.util.function.Consumer;
import net.minecraft.world.level.block.BaseFireBlock;

public enum InsideBlockEffectType {
   FREEZE(entity -> {
      entity.setIsInPowderSnow(true);
      if (entity.canFreeze()) {
         entity.setTicksFrozen(Math.min(entity.getTicksRequiredToFreeze(), entity.getTicksFrozen() + 1));
      }
   }),
   CLEAR_FREEZE(Entity::clearFreeze),
   FIRE_IGNITE(BaseFireBlock::fireIgnite),
   LAVA_IGNITE(Entity::lavaIgnite),
   EXTINGUISH(Entity::clearFire);

   private final Consumer<Entity> effect;

   InsideBlockEffectType(final Consumer<Entity> effect) {
      this.effect = effect;
   }

   public Consumer<Entity> effect() {
      return this.effect;
   }
}
```

## 皮革靴与细雪行走

原始证据：[JSON](mc-knowledge/evidence/bf8a6147c7e88afb1d032e8aee855356e11cc65bc39f965bde6ba72090e29a86.json)

```java
public class PowderSnowBlock extends Block implements BucketPickup {
   public static boolean canEntityWalkOnPowderSnow(final Entity entity) {
      if (entity.is(EntityTypeTags.POWDER_SNOW_WALKABLE_MOBS)) {
         return true;
      } else {
         return entity instanceof LivingEntity livingEntity ? livingEntity.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS) : false;
      }
   }
}
```

## ConversionParams.single：装备保留参数

原始证据：[JSON](mc-knowledge/evidence/2b7966fe271587d9a20a76cd1b814230c78ceba2e7c1ab730c4e48de20bc8ca3.json)

```java
public record ConversionParams(ConversionType type, boolean keepEquipment, boolean preserveCanPickUpLoot, @Nullable PlayerTeam team) {
   public static ConversionParams single(final Mob mob, final boolean keepEquipment, final boolean preserveCanPickUpLoot) {
      return new ConversionParams(ConversionType.SINGLE, keepEquipment, preserveCanPickUpLoot, mob.getTeam());
   }
}
```

## ConversionType：装备转移实现

原始证据：[JSON](mc-knowledge/evidence/4ca6696ba5fb00a1a6ee017a4c10330bb0e24a652d3ae8a33c5eb5882f491405.json)

```java
package net.minecraft.world.entity;

import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Scoreboard;

public enum ConversionType {
   SINGLE(true) {
      @Override
      public void convert(final Mob from, final Mob to, final ConversionParams params) {
         Entity rootPassenger = from.getFirstPassenger();
         to.copyPosition(from);
         to.setDeltaMovement(from.getDeltaMovement());
         if (rootPassenger != null) {
            rootPassenger.stopRiding();
            rootPassenger.boardingCooldown = 0;

            for (Entity passenger : to.getPassengers()) {
               passenger.stopRiding();
               passenger.remove(RemovalReason.DISCARDED);
            }

            rootPassenger.startRiding(to);
         }

         Entity vehicle = from.getVehicle();
         if (vehicle != null) {
            from.stopRiding();
            to.startRiding(vehicle, false, false);
         }

         if (params.keepEquipment()) {
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
               ItemStack itemStack = from.getItemBySlot(slot);
               if (!itemStack.isEmpty()) {
                  to.setItemSlot(slot, itemStack.copyAndClear());
                  to.setDropChance(slot, from.getDropChances().byEquipment(slot));
               }
            }
         }

         to.fallDistance = from.fallDistance;
         to.setSharedFlag(7, from.isFallFlying());
         to.lastHurtByPlayerMemoryTime = from.lastHurtByPlayerMemoryTime;
         to.hurtTime = from.hurtTime;
         to.yBodyRot = from.yBodyRot;
         to.setOnGround(from.onGround());
         from.getSleepingPos().ifPresent(to::setSleepingPos);
         Entity leashHolder = from.getLeashHolder();
         if (leashHolder != null) {
            to.setLeashedTo(leashHolder, true);
         }

         ConversionType.convertCommon(from, to, params);
      }
   },
   SPLIT_ON_DEATH(false) {
      @Override
      public void convert(final Mob from, final Mob to, final ConversionParams params) {
         Entity rootPassenger = from.getFirstPassenger();
         if (rootPassenger != null) {
            rootPassenger.stopRiding();
         }

         Entity leashHolder = from.getLeashHolder();
         if (leashHolder != null) {
            from.dropLeash();
         }

         ConversionType.convertCommon(from, to, params);
      }
   };

   private static final Set<DataComponentType<?>> COMPONENTS_TO_COPY = Set.of(DataComponents.CUSTOM_NAME, DataComponents.CUSTOM_DATA);
   private final boolean discardAfterConversion;

   ConversionType(final boolean discardAfterConversion) {
      this.discardAfterConversion = discardAfterConversion;
   }

   public boolean shouldDiscardAfterConversion() {
      return this.discardAfterConversion;
   }

   public abstract void convert(Mob from, Mob to, ConversionParams params);

   private static void convertCommon(final Mob from, final Mob to, final ConversionParams params) {
      to.setAbsorptionAmount(from.getAbsorptionAmount());

      for (MobEffectInstance effect : from.getActiveEffects()) {
         to.addEffect(new MobEffectInstance(effect));
      }

      if (from.isBaby()) {
         to.setBaby(true);
      }

      if (from instanceof AgeableMob oldAgeable && to instanceof AgeableMob convertedAgeable) {
         convertedAgeable.setAge(oldAgeable.getAge());
         convertedAgeable.forcedAge = oldAgeable.forcedAge;
         convertedAgeable.forcedAgeTimer = oldAgeable.forcedAgeTimer;
      }

      Brain<?> oldBrain = from.getBrain();
      Brain<?> convertedBrain = to.getBrain();
      if (oldBrain.checkMemory(MemoryModuleType.ANGRY_AT, MemoryStatus.REGISTERED) && oldBrain.hasMemoryValue(MemoryModuleType.ANGRY_AT)) {
         convertedBrain.setMemory(MemoryModuleType.ANGRY_AT, oldBrain.getMemory(MemoryModuleType.ANGRY_AT));
      }

      if (params.preserveCanPickUpLoot()) {
         to.setCanPickUpLoot(from.canPickUpLoot());
      }

      to.setLeftHanded(from.isLeftHanded());
      to.setNoAi(from.isNoAi());
      if (from.isPersistenceRequired()) {
         to.setPersistenceRequired();
      }

      to.setCustomNameVisible(from.isCustomNameVisible());
      to.setSharedFlagOnFire(from.isOnFire());
      to.setPermanentlyInvulnerable(from.isPermanentlyInvulnerable());
```

## OwnerHurtByTargetGoal.canUse

原始证据：[JSON](mc-knowledge/evidence/4d71e46293f67965bcd63d1b4821d6e64e6abc10bdc15a46cf8190cc156ff9ba.json)

```java
public class OwnerHurtByTargetGoal extends TargetGoal {
   @Override
   public boolean canUse() {
      if (this.tameAnimal.isTame() && !this.tameAnimal.isOrderedToSit()) {
         LivingEntity owner = this.tameAnimal.getOwner();
         if (owner == null) {
            return false;
         } else {
            DamageSource lastDamageSource = owner.getLastDamageSource(100);
            if (lastDamageSource != null && !lastDamageSource.is(DamageTypeTags.NO_WOLF_RETALIATION)) {
               this.ownerLastHurtBy = owner.getLastHurtByMob();
               int ts = owner.getLastHurtByMobTimestamp();
               return ts != this.timestamp
                  && this.canAttack(this.ownerLastHurtBy, TargetingConditions.DEFAULT)
                  && this.tameAnimal.wantsToAttack(this.ownerLastHurtBy, owner);
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }
}
```

## OwnerHurtTargetGoal.canUse

原始证据：[JSON](mc-knowledge/evidence/c59c622f9b2a9808244931ef884c78f7d9da7ff48a08f9d9c129cc4bfab254de.json)

```java
public class OwnerHurtTargetGoal extends TargetGoal {
   @Override
   public boolean canUse() {
      if (this.tameAnimal.isTame() && !this.tameAnimal.isOrderedToSit()) {
         LivingEntity owner = this.tameAnimal.getOwner();
         if (owner == null) {
            return false;
         }

         this.ownerLastHurt = owner.getLastHurtMob();
         int ts = owner.getLastHurtMobTimestamp();
         return ts != this.timestamp
            && this.canAttack(this.ownerLastHurt, TargetingConditions.DEFAULT)
            && this.tameAnimal.wantsToAttack(this.ownerLastHurt, owner);
      } else {
         return false;
      }
   }
}
```

## Wolf.wantsToAttack

原始证据：[JSON](mc-knowledge/evidence/b14983abd4f35a621bc0b2977bc6d88dac63f212f7f5af7e247afc9937e57ee6.json)

```java
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
```

## MeleeAttackGoal.canUse：寻路与攻击距离

原始证据：[JSON](mc-knowledge/evidence/6f99428c8499afdd8c074be121df46eadf24e5d417352315cbea5b141c3a9db6.json)

```java
public class MeleeAttackGoal extends Goal {
   @Override
   public boolean canUse() {
      long time = this.mob.level().getGameTime();
      if (time - this.lastCanUseCheck < 20L) {
         return false;
      }

      this.lastCanUseCheck = time;
      LivingEntity target = this.mob.getTarget();
      if (target == null) {
         return false;
      }

      if (!target.isAlive()) {
         return false;
      }

      this.path = this.mob.getNavigation().createPath(target, 0);
      return this.path != null ? true : this.mob.isWithinMeleeAttackRange(target);
   }
}
```

