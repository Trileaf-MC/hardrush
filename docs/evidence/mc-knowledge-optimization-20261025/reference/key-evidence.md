# 精简关键证据（原文；行号为CLI缓存成员视图行号）

所有输出和完整出处元数据保存在raw/；原始查询在queries.jsonl。下列text保留相关成员完整正文，避免截断关键分支。

## golem_attributes

出处：net.minecraft.world.entity.animal.golem.IronGolem / createAttributes；returned_range={'start_line': 11, 'start_char': 0, 'end_line': 21, 'end_char': 0}；原输出truncated=False。

```java
public class IronGolem extends AbstractGolem implements NeutralMob {
   public static Builder createAttributes() {
      return Mob.createMobAttributes()
         .add(Attributes.MAX_HEALTH, 100.0)
         .add(Attributes.MOVEMENT_SPEED, 0.25)
         .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
         .add(Attributes.ATTACK_DAMAGE, 15.0)
         .add(Attributes.STEP_HEIGHT, 1.0);
   }
}
```

## golem_getattack

出处：net.minecraft.world.entity.animal.golem.IronGolem / getAttackDamage；returned_range={'start_line': 9, 'start_char': 0, 'end_line': 14, 'end_char': 0}；原输出truncated=False。

```java
public class IronGolem extends AbstractGolem implements NeutralMob {
   private float getAttackDamage() {
      return (float)this.getAttributeValue(Attributes.ATTACK_DAMAGE);
   }
}
```

## golem_attack

出处：net.minecraft.world.entity.animal.golem.IronGolem / doHurtTarget；returned_range={'start_line': 14, 'start_char': 0, 'end_line': 34, 'end_char': 0}；原输出truncated=False。

```java
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
```

## golem_attack_bytecode

出处：net.minecraft.world.entity.animal.golem.IronGolem / {'name': 'doHurtTarget', 'descriptor': '(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z'}；returned_range={'start_line': 1, 'start_char': 0, 'end_line': 83, 'end_char': 0}；原输出truncated=False。

```java
  public boolean doHurtTarget(net.minecraft.server.level.ServerLevel, net.minecraft.world.entity.Entity);
    descriptor: (Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z
    Code:
         0: aload_0
         1: bipush        10
         3: putfield      #174                // Field attackAnimationTick:I
         6: aload_1
         7: aload_0
         8: iconst_4
         9: invokevirtual #296                // Method net/minecraft/server/level/ServerLevel.broadcastEntityEvent:(Lnet/minecraft/world/entity/Entity;B)V
        12: aload_0
        13: invokevirtual #300                // Method getAttackDamage:()F
        16: fstore_3
        17: fload_3
        18: f2i
        19: ifle          41
        22: fload_3
        23: fconst_2
        24: fdiv
        25: aload_0
        26: getfield      #211                // Field random:Lnet/minecraft/util/RandomSource;
        29: fload_3
        30: f2i
        31: invokeinterface #154,  2          // InterfaceMethod net/minecraft/util/RandomSource.nextInt:(I)I
        36: i2f
        37: fadd
        38: goto          42
        41: fload_3
        42: fstore        4
        44: aload_0
        45: invokevirtual #304                // Method damageSources:()Lnet/minecraft/world/damagesource/DamageSources;
        48: aload_0
        49: invokevirtual #308                // Method net/minecraft/world/damagesource/DamageSources.mobAttack:(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/damagesource/DamageSource;
        52: astore        5
        54: aload_2
        55: aload_1
        56: aload         5
        58: fload         4
        60: invokevirtual #314                // Method net/minecraft/world/entity/Entity.hurtServer:(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z
        63: istore        6
        65: iload         6
        67: ifeq          133
        70: aload_2
        71: instanceof    #160                // class net/minecraft/world/entity/LivingEntity
        74: ifeq          94
        77: aload_2
        78: checkcast     #160                // class net/minecraft/world/entity/LivingEntity
        81: astore        9
        83: aload         9
        85: getstatic     #135                // Field net/minecraft/world/entity/ai/attributes/Attributes.KNOCKBACK_RESISTANCE:Lnet/minecraft/core/Holder;
        88: invokevirtual #320                // Method net/minecraft/world/entity/LivingEntity.getAttributeValue:(Lnet/minecraft/core/Holder;)D
        91: goto          95
        94: dconst_0
        95: dstore        7
        97: dconst_0
        98: dconst_1
        99: dload         7
       101: dsub
       102: invokestatic  #321                // Method java/lang/Math.max:(DD)D
       105: dstore        9
       107: aload_2
       108: aload_2
       109: invokevirtual #327                // Method net/minecraft/world/entity/Entity.getDeltaMovement:()Lnet/minecraft/world/phys/Vec3;
       112: dconst_0
       113: ldc2_w        #328                // double 0.4000000059604645d
       116: dload         9
       118: dmul
       119: dconst_0
       120: invokevirtual #330                // Method net/minecraft/world/phys/Vec3.add:(DDD)Lnet/minecraft/world/phys/Vec3;
       123: invokevirtual #333                // Method net/minecraft/world/entity/Entity.setDeltaMovement:(Lnet/minecraft/world/phys/Vec3;)V
       126: aload_1
       127: aload_2
       128: aload         5
       130: invokestatic  #337                // Method net/minecraft/world/item/enchantment/EnchantmentHelper.doPostAttackEffects:(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;)V
       133: aload_0
       134: getstatic     #343                // Field net/minecraft/sounds/SoundEvents.IRON_GOLEM_ATTACK:Lnet/minecraft/sounds/SoundEvent;
       137: fconst_1
       138: fconst_1
       139: invokevirtual #349                // Method playSound:(Lnet/minecraft/sounds/SoundEvent;FF)V
       142: iload         6
       144: ireturn

```

## bit_nextint

出处：net.minecraft.world.level.levelgen.BitRandomSource / nextInt；returned_range={'start_line': 5, 'start_char': 0, 'end_line': 26, 'end_char': 0}；原输出truncated=False。

```java
public interface BitRandomSource extends RandomSource {
   @Override
   default int nextInt(final int bound) {
      if (bound <= 0) {
         throw new IllegalArgumentException("Bound must be positive");
      }

      if ((bound & bound - 1) == 0) {
         return (int)((long)bound * this.next(31) >> 31);
      }

      int sample;
      int modulo;
      do {
         sample = this.next(31);
         modulo = sample % bound;
      } while (sample - modulo + (bound - 1) < 0);

      return modulo;
   }
}
```

## mob_attack_data

出处：data/minecraft/damage_type/mob_attack.json / {}；returned_range={'start_line': 1, 'start_char': 0, 'end_line': 6, 'end_char': 0}；原输出truncated=False。

```java
{
  "exhaustion": 0.1,
  "message_id": "mob",
  "scaling": "when_caused_by_living_non_player"
}
```

## scales_difficulty

出处：net.minecraft.world.damagesource.DamageSource / scalesWithDifficulty；returned_range={'start_line': 9, 'start_char': 0, 'end_line': 18, 'end_char': 0}；原输出truncated=False。

```java
public class DamageSource {
   public boolean scalesWithDifficulty() {
      return switch (this.type().scaling()) {
         case NEVER -> false;
         case WHEN_CAUSED_BY_LIVING_NON_PLAYER -> this.causingEntity instanceof LivingEntity && !(this.causingEntity instanceof Player);
         case ALWAYS -> true;
      };
   }
}
```

## player_hurt

出处：net.minecraft.world.entity.player.Player / hurtServer；returned_range={'start_line': 23, 'start_char': 0, 'end_line': 57, 'end_char': 0}；原输出truncated=False。

```java
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
```

## player_actual

出处：net.minecraft.world.entity.player.Player / actuallyHurt；returned_range={'start_line': 23, 'start_char': 0, 'end_line': 50, 'end_char': 0}；原输出truncated=False。

```java
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
```

## living_sethealth

出处：net.minecraft.world.entity.LivingEntity / setHealth；returned_range={'start_line': 37, 'start_char': 0, 'end_line': 42, 'end_char': 0}；原输出truncated=False。

```java
public abstract class LivingEntity extends Entity implements Attackable, WaypointTransmitter {
   public void setHealth(final float health) {
      this.entityData.set(DATA_HEALTH_ID, Mth.clamp(health, 0.0F, this.getMaxHealth()));
   }
}
```

## spawner_ctor

出处：net.minecraft.world.level.BaseSpawner / {'name': '<init>', 'descriptor': '()V'}；returned_range={'start_line': 1, 'start_char': 0, 'end_line': 32, 'end_char': 0}；原输出truncated=False。

```java
  public net.minecraft.world.level.BaseSpawner();
    descriptor: ()V
    Code:
         0: aload_0
         1: invokespecial #3                  // Method java/lang/Object."<init>":()V
         4: aload_0
         5: bipush        20
         7: putfield      #11                 // Field spawnDelay:I
        10: aload_0
        11: invokestatic  #15                 // Method net/minecraft/util/random/WeightedList.of:()Lnet/minecraft/util/random/WeightedList;
        14: putfield      #21                 // Field spawnPotentials:Lnet/minecraft/util/random/WeightedList;
        17: aload_0
        18: sipush        200
        21: putfield      #25                 // Field minSpawnDelay:I
        24: aload_0
        25: sipush        800
        28: putfield      #28                 // Field maxSpawnDelay:I
        31: aload_0
        32: iconst_4
        33: putfield      #31                 // Field spawnCount:I
        36: aload_0
        37: bipush        6
        39: putfield      #34                 // Field maxNearbyEntities:I
        42: aload_0
        43: bipush        16
        45: putfield      #37                 // Field requiredPlayerRange:I
        48: aload_0
        49: iconst_4
        50: putfield      #40                 // Field spawnRange:I
        53: return

```

## spawner_load

出处：net.minecraft.world.level.BaseSpawner / load；returned_range={'start_line': 11, 'start_char': 0, 'end_line': 26, 'end_char': 0}；原输出truncated=False。

```java
public abstract class BaseSpawner {
   public void load(final @Nullable Level level, final BlockPos pos, final ValueInput input) {
      this.spawnDelay = input.getShortOr("Delay", (short)20);
      input.<SpawnData>read("SpawnData", SpawnData.CODEC).ifPresent(nextSpawnData -> this.setNextSpawnData(level, pos, nextSpawnData));
      this.spawnPotentials = input.<WeightedList<SpawnData>>read("SpawnPotentials", SpawnData.LIST_CODEC)
         .orElseGet(() -> WeightedList.of(this.nextSpawnData != null ? this.nextSpawnData : new SpawnData()));
      this.minSpawnDelay = input.getIntOr("MinSpawnDelay", 200);
      this.maxSpawnDelay = input.getIntOr("MaxSpawnDelay", 800);
      this.spawnCount = input.getIntOr("SpawnCount", 4);
      this.maxNearbyEntities = input.getIntOr("MaxNearbyEntities", 6);
      this.requiredPlayerRange = input.getIntOr("RequiredPlayerRange", 16);
      this.spawnRange = input.getIntOr("SpawnRange", 4);
      this.displayEntity = null;
   }
}
```

## spawner_near

出处：net.minecraft.world.level.BaseSpawner / isNearPlayer；returned_range={'start_line': 8, 'start_char': 0, 'end_line': 13, 'end_char': 0}；原输出truncated=False。

```java
public abstract class BaseSpawner {
   private boolean isNearPlayer(final Level level, final BlockPos pos) {
      return level.hasNearbyAlivePlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, this.requiredPlayerRange);
   }
}
```

## near_alive

出处：net.minecraft.world.level.EntityGetter / hasNearbyAlivePlayer；returned_range={'start_line': 11, 'start_char': 0, 'end_line': 25, 'end_char': 0}；原输出truncated=False。

```java
public interface EntityGetter {
   default boolean hasNearbyAlivePlayer(final double x, final double y, final double z, final double range) {
      for (Player player : this.players()) {
         if (EntitySelector.NO_SPECTATORS.test(player) && EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(player)) {
            double playerDist = player.distanceToSqr(x, y, z);
            if (range < 0.0 || playerDist < range * range) {
               return true;
            }
         }
      }

      return false;
   }
}
```

## spawner_tick

出处：net.minecraft.world.level.BaseSpawner / serverTick；returned_range={'start_line': 24, 'start_char': 0, 'end_line': 131, 'end_char': 0}；原输出truncated=False。

```java
public abstract class BaseSpawner {
   public void serverTick(final ServerLevel level, final BlockPos pos) {
      if (this.isNearPlayer(level, pos) && level.isSpawnerBlockEnabled()) {
         if (this.spawnDelay == -1) {
            this.delay(level, pos);
         }

         if (this.spawnDelay > 0) {
            this.spawnDelay--;
         } else {
            boolean delay = false;
            RandomSource random = level.getRandom();
            SpawnData nextSpawnData = this.getOrCreateNextSpawnData(level, random, pos);

            for (int c = 0; c < this.spawnCount; c++) {
               try (ScopedCollector reporter = new ScopedCollector(this::toString, LOGGER)) {
                  ValueInput input = TagValueInput.create(reporter, level.registryAccess(), nextSpawnData.getEntityToSpawn());
                  Optional<EntityType<?>> entityType = EntityType.by(input);
                  if (entityType.isEmpty()) {
                     this.delay(level, pos);
                     return;
                  }

                  Vec3 spawnPos = input.<Vec3>read("Pos", Vec3.CODEC)
                     .orElseGet(
                        () -> new Vec3(
                           pos.getX() + (random.nextDouble() - random.nextDouble()) * this.spawnRange + 0.5,
                           pos.getY() + random.nextInt(3) - 1,
                           pos.getZ() + (random.nextDouble() - random.nextDouble()) * this.spawnRange + 0.5
                        )
                     );
                  if (level.noCollision(entityType.get().getSpawnAABB(spawnPos.x, spawnPos.y, spawnPos.z))) {
                     BlockPos spawnBlockPos = BlockPos.containing(spawnPos);
                     if (nextSpawnData.getCustomSpawnRules().isPresent()) {
                        if (!entityType.get().getCategory().isFriendly() && level.getDifficulty() == Difficulty.PEACEFUL) {
                           continue;
                        }

                        CustomSpawnRules customSpawnRules = nextSpawnData.getCustomSpawnRules().get();
                        if (!customSpawnRules.isValidPosition(spawnBlockPos, level)) {
                           continue;
                        }
                     } else if (!SpawnPlacements.checkSpawnRules(entityType.get(), level, EntitySpawnReason.SPAWNER, spawnBlockPos, level.getRandom())) {
                        continue;
                     }

                     Entity entity = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.SPAWNER, e -> {
                        e.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, e.getYRot(), e.getXRot());
                        return e;
                     });
                     if (entity == null) {
                        this.delay(level, pos);
                        return;
                     }

                     int nearBy = level.getEntities(
                           EntityTypeTest.forExactClass(entity.getClass()),
                           new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1).inflate(this.spawnRange),
                           EntitySelector.NO_SPECTATORS
                        )
                        .size();
                     if (nearBy >= this.maxNearbyEntities) {
                        this.delay(level, pos);
                        return;
                     }

                     entity.snapTo(entity.getX(), entity.getY(), entity.getZ(), random.nextFloat() * 360.0F, 0.0F);
                     if (entity instanceof Mob mob) {
                        if (nextSpawnData.getCustomSpawnRules().isEmpty() && !mob.checkSpawnRules(level, EntitySpawnReason.SPAWNER)
                           || !mob.checkSpawnObstruction(level)) {
                           continue;
                        }

                        boolean hasNoConfiguration = nextSpawnData.getEntityToSpawn().size() == 1
                           && nextSpawnData.getEntityToSpawn().getString("id").isPresent();
                        if (hasNoConfiguration) {
                           ((Mob)entity).finalizeSpawn(level, level.getCurrentDifficultyAt(entity.blockPosition()), EntitySpawnReason.SPAWNER, null);
                        }

                        nextSpawnData.getEquipment().ifPresent(mob::equip);
                     }

                     if (!level.tryAddFreshEntityWithPassengers(entity)) {
                        this.delay(level, pos);
                        return;
                     }

                     level.levelEvent(2004, pos, 0);
                     level.gameEvent(entity, GameEvent.ENTITY_PLACE, spawnBlockPos);
                     if (entity instanceof Mob mob) {
                        mob.spawnAnim();
                     }

                     delay = true;
                  }
               }
            }

            if (delay) {
               this.delay(level, pos);
            }

            return;
         }
      }
   }
}
```

## spawner_delay

出处：net.minecraft.world.level.BaseSpawner / delay；returned_range={'start_line': 9, 'start_char': 0, 'end_line': 22, 'end_char': 0}；原输出truncated=False。

```java
public abstract class BaseSpawner {
   private void delay(final Level level, final BlockPos pos) {
      RandomSource random = level.random;
      if (this.maxSpawnDelay <= this.minSpawnDelay) {
         this.spawnDelay = this.minSpawnDelay;
      } else {
         this.spawnDelay = this.minSpawnDelay + random.nextInt(this.maxSpawnDelay - this.minSpawnDelay);
      }

      this.spawnPotentials.getRandom(random).ifPresent(entry -> this.setNextSpawnData(level, pos, entry));
      this.broadcastEvent(level, pos, 1);
   }
}
```

## trial_fullconfig_tail

出处：net.minecraft.world.level.block.entity.trialspawner.TrialSpawner$FullConfig / {}；returned_range={'start_line': 400, 'start_char': 0, 'end_line': 438, 'end_char': 0}；原输出truncated=False。

```java
                  .optionalFieldOf("ominous_config", Holder.direct(TrialSpawnerConfig.DEFAULT))
                  .forGetter(TrialSpawner.FullConfig::ominous),
               ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("target_cooldown_length", 36000).forGetter(TrialSpawner.FullConfig::targetCooldownLength),
               Codec.intRange(1, 128).optionalFieldOf("required_player_range", 14).forGetter(TrialSpawner.FullConfig::requiredPlayerRange)
            )
            .apply(i, TrialSpawner.FullConfig::new)
      );
      public static final TrialSpawner.FullConfig DEFAULT = new TrialSpawner.FullConfig(
         Holder.direct(TrialSpawnerConfig.DEFAULT), Holder.direct(TrialSpawnerConfig.DEFAULT), 36000, 14
      );

      public TrialSpawner.FullConfig overrideEntity(final EntityType<?> type) {
         return new TrialSpawner.FullConfig(
            Holder.direct(this.normal.value().withSpawning(type)),
            Holder.direct(this.ominous.value().withSpawning(type)),
            this.targetCooldownLength,
            this.requiredPlayerRange
         );
      }

      public TrialSpawner.FullConfig overrideEntityData(final TypedEntityData<EntityType<?>> entityData) {
         return new TrialSpawner.FullConfig(
            Holder.direct(this.normal.value().withSpawning(entityData)),
            Holder.direct(this.ominous.value().withSpawning(entityData)),
            this.targetCooldownLength,
            this.requiredPlayerRange
         );
      }
   }

   public interface StateAccessor {
      void setState(Level level, TrialSpawnerState state);

      TrialSpawnerState getState();

      void markUpdated();
   }
}
```

## trial_detect

出处：net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData / tryDetectPlayers；returned_range={'start_line': 22, 'start_char': 0, 'end_line': 63, 'end_char': 0}；原输出truncated=False。

```java
public class TrialSpawnerStateData {
   public void tryDetectPlayers(final ServerLevel level, final BlockPos pos, final TrialSpawner trialSpawner) {
      boolean isThrottled = (pos.asLong() + level.getGameTime()) % 20L != 0L;
      if (!isThrottled) {
         if (!trialSpawner.getState().equals(TrialSpawnerState.COOLDOWN) || !trialSpawner.isOminous()) {
            List<UUID> inLineOfSightPlayers = trialSpawner.getPlayerDetector()
               .detect(level, trialSpawner.getEntitySelector(), pos, trialSpawner.getRequiredPlayerRange(), true);
            boolean becameOminous;
            if (!trialSpawner.isOminous() && !inLineOfSightPlayers.isEmpty()) {
               Optional<Pair<Player, Holder<MobEffect>>> playerWithOminousEffect = findPlayerWithOminousEffect(level, inLineOfSightPlayers);
               playerWithOminousEffect.ifPresent(playerAndEffect -> {
                  Player player = (Player)playerAndEffect.getFirst();
                  if (playerAndEffect.getSecond() == MobEffects.BAD_OMEN) {
                     transformBadOmenIntoTrialOmen(player);
                  }

                  level.levelEvent(3020, BlockPos.containing(player.getEyePosition()), 0);
                  trialSpawner.applyOminous(level, pos);
               });
               becameOminous = playerWithOminousEffect.isPresent();
            } else {
               becameOminous = false;
            }

            if (!trialSpawner.getState().equals(TrialSpawnerState.COOLDOWN) || becameOminous) {
               boolean isSearchingForFirstPlayer = trialSpawner.getStateData().detectedPlayers.isEmpty();
               List<UUID> foundPlayers = isSearchingForFirstPlayer
                  ? inLineOfSightPlayers
                  : trialSpawner.getPlayerDetector().detect(level, trialSpawner.getEntitySelector(), pos, trialSpawner.getRequiredPlayerRange(), false);
               if (this.detectedPlayers.addAll(foundPlayers)) {
                  this.nextMobSpawnsAt = Math.max(level.getGameTime() + 40L, this.nextMobSpawnsAt);
                  if (!becameOminous) {
                     int event = trialSpawner.isOminous() ? 3019 : 3013;
                     level.levelEvent(event, pos, this.detectedPlayers.size());
                  }
               }
            }
         }
      }
   }
}
```

## trial_state_tick

出处：net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState / tickAndGetNext；returned_range={'start_line': 19, 'start_char': 0, 'end_line': 115, 'end_char': 0}；原输出truncated=False。

```java
public enum TrialSpawnerState implements StringRepresentable {
   TrialSpawnerState tickAndGetNext(final BlockPos spawnerPos, final TrialSpawner trialSpawner, final ServerLevel serverLevel) {
      TrialSpawnerStateData data = trialSpawner.getStateData();
      TrialSpawnerConfig config = trialSpawner.activeConfig();
      RandomSource random = serverLevel.getRandom();

      return switch (this) {
         case INACTIVE -> data.getOrCreateDisplayEntity(trialSpawner, serverLevel, WAITING_FOR_PLAYERS) == null ? this : WAITING_FOR_PLAYERS;
         case WAITING_FOR_PLAYERS -> {
            if (!trialSpawner.canSpawnInLevel(serverLevel)) {
               data.resetStatistics();
               yield this;
            } else if (!data.hasMobToSpawn(trialSpawner, random)) {
               yield INACTIVE;
            } else {
               data.tryDetectPlayers(serverLevel, spawnerPos, trialSpawner);
               yield data.detectedPlayers.isEmpty() ? this : ACTIVE;
            }
         }
         case ACTIVE -> {
            if (!trialSpawner.canSpawnInLevel(serverLevel)) {
               data.resetStatistics();
               yield WAITING_FOR_PLAYERS;
            } else if (!data.hasMobToSpawn(trialSpawner, random)) {
               yield INACTIVE;
            } else {
               int additionalPlayers = data.countAdditionalPlayers(spawnerPos);
               data.tryDetectPlayers(serverLevel, spawnerPos, trialSpawner);
               if (trialSpawner.isOminous()) {
                  this.spawnOminousOminousItemSpawner(serverLevel, spawnerPos, trialSpawner);
               }

               if (data.hasFinishedSpawningAllMobs(config, additionalPlayers)) {
                  if (data.haveAllCurrentMobsDied()) {
                     data.cooldownEndsAt = serverLevel.getGameTime() + trialSpawner.getTargetCooldownLength();
                     data.totalMobsSpawned = 0;
                     data.nextMobSpawnsAt = 0L;
                     yield WAITING_FOR_REWARD_EJECTION;
                  }
               } else if (data.isReadyToSpawnNextMob(serverLevel, config, additionalPlayers)) {
                  trialSpawner.spawnMob(serverLevel, spawnerPos).ifPresent(entityId -> {
                     data.currentMobs.add(entityId);
                     data.totalMobsSpawned++;
                     data.nextMobSpawnsAt = serverLevel.getGameTime() + config.ticksBetweenSpawn();
                     config.spawnPotentialsDefinition().getRandom(random).ifPresent(entry -> {
                        data.nextSpawnData = Optional.of(entry);
                        trialSpawner.markUpdated();
                     });
                  });
               }

               yield this;
            }
         }
         case WAITING_FOR_REWARD_EJECTION -> {
            if (data.isReadyToOpenShutter(serverLevel, 40.0F, trialSpawner.getTargetCooldownLength())) {
               serverLevel.playSound(null, spawnerPos, SoundEvents.TRIAL_SPAWNER_OPEN_SHUTTER, SoundSource.BLOCKS);
               yield EJECTING_REWARD;
            } else {
               yield this;
            }
         }
         case EJECTING_REWARD -> {
            if (!data.isReadyToEjectItems(serverLevel, TIME_BETWEEN_EACH_EJECTION, trialSpawner.getTargetCooldownLength())) {
               yield this;
            } else if (data.detectedPlayers.isEmpty()) {
               serverLevel.playSound(null, spawnerPos, SoundEvents.TRIAL_SPAWNER_CLOSE_SHUTTER, SoundSource.BLOCKS);
               data.ejectingLootTable = Optional.empty();
               yield COOLDOWN;
            } else {
               if (data.ejectingLootTable.isEmpty()) {
                  data.ejectingLootTable = config.lootTablesToEject().getRandom(random);
               }

               data.ejectingLootTable.ifPresent(lootTable -> trialSpawner.ejectReward(serverLevel, spawnerPos, (ResourceKey<LootTable>)lootTable));
               data.detectedPlayers.remove(data.detectedPlayers.iterator().next());
               yield this;
            }
         }
         case COOLDOWN -> {
            data.tryDetectPlayers(serverLevel, spawnerPos, trialSpawner);
            if (!data.detectedPlayers.isEmpty()) {
               data.totalMobsSpawned = 0;
               data.nextMobSpawnsAt = 0L;
               yield ACTIVE;
            } else if (data.isCooldownFinished(serverLevel)) {
               trialSpawner.removeOminous(serverLevel, spawnerPos);
               data.reset();
               yield WAITING_FOR_PLAYERS;
            } else {
               yield this;
            }
         }
      };
   }
}
```

## trial_ready

出处：net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData / isReadyToSpawnNextMob；returned_range={'start_line': 20, 'start_char': 0, 'end_line': 25, 'end_char': 0}；原输出truncated=False。

```java
public class TrialSpawnerStateData {
   public boolean isReadyToSpawnNextMob(final ServerLevel serverLevel, final TrialSpawnerConfig config, final int additionalPlayers) {
      return serverLevel.getGameTime() >= this.nextMobSpawnsAt && this.currentMobs.size() < config.calculateTargetSimultaneousMobs(additionalPlayers);
   }
}
```

## trial_config_values

出处：net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerConfig / {}；returned_range={'start_line': 1, 'start_char': 0, 'end_line': 181, 'end_char': 0}；原输出truncated=True。

```java
// 摘录：原视图第59–65及108–116行
   public int calculateTargetTotalMobs(final int additionalPlayers) {
      return (int)Math.floor(this.totalMobs + this.totalMobsAddedPerPlayer * additionalPlayers);
   }

   public int calculateTargetSimultaneousMobs(final int additionalPlayers) {
      return (int)Math.floor(this.simultaneousMobs + this.simultaneousMobsAddedPerPlayer * additionalPlayers);
   }

   public static class Builder {
      private int spawnRange = 4;
      private float totalMobs = 6.0F;
      private float simultaneousMobs = 2.0F;
      private float totalMobsAddedPerPlayer = 2.0F;
      private float simultaneousMobsAddedPerPlayer = 1.0F;
      private int ticksBetweenSpawn = 40;
      private WeightedList<SpawnData> spawnPotentialsDefinition = WeightedList.of();
```

## trial_spawn

出处：net.minecraft.world.level.block.entity.trialspawner.TrialSpawner / spawnMob；returned_range={'start_line': 27, 'start_char': 0, 'end_line': 104, 'end_char': 0}；原输出truncated=False。

```java
public final class TrialSpawner {
   public Optional<UUID> spawnMob(final ServerLevel level, final BlockPos spawnerPos) {
      RandomSource random = level.getRandom();
      SpawnData nextSpawnData = this.data.getOrCreateNextSpawnData(this, level.getRandom());

      try (ScopedCollector reporter = new ScopedCollector(() -> "spawner@" + spawnerPos, LOGGER)) {
         ValueInput input = TagValueInput.create(reporter, level.registryAccess(), nextSpawnData.entityToSpawn());
         Optional<EntityType<?>> entityType = EntityType.by(input);
         if (entityType.isEmpty()) {
            return Optional.empty();
         }

         Vec3 spawnPos = input.<Vec3>read("Pos", Vec3.CODEC)
            .orElseGet(
               () -> {
                  TrialSpawnerConfig activeConfig = this.activeConfig();
                  return new Vec3(
                     spawnerPos.getX() + (random.nextDouble() - random.nextDouble()) * activeConfig.spawnRange() + 0.5,
                     spawnerPos.getY() + random.nextInt(3) - 1,
                     spawnerPos.getZ() + (random.nextDouble() - random.nextDouble()) * activeConfig.spawnRange() + 0.5
                  );
               }
            );
         if (!level.noCollision(entityType.get().getSpawnAABB(spawnPos.x, spawnPos.y, spawnPos.z))) {
            return Optional.empty();
         }

         if (!inLineOfSight(level, Vec3.atCenterOf(spawnerPos), spawnPos)) {
            return Optional.empty();
         }

         BlockPos spawnBlockPos = BlockPos.containing(spawnPos);
         if (!SpawnPlacements.checkSpawnRules(entityType.get(), level, EntitySpawnReason.TRIAL_SPAWNER, spawnBlockPos, level.getRandom())) {
            return Optional.empty();
         }

         if (nextSpawnData.getCustomSpawnRules().isPresent()) {
            CustomSpawnRules customSpawnRules = nextSpawnData.getCustomSpawnRules().get();
            if (!customSpawnRules.isValidPosition(spawnBlockPos, level)) {
               return Optional.empty();
            }
         }

         Entity entity = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.TRIAL_SPAWNER, e -> {
            e.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, random.nextFloat() * 360.0F, 0.0F);
            return e;
         });
         if (entity == null) {
            return Optional.empty();
         }

         if (entity instanceof Mob mob) {
            if (!mob.checkSpawnObstruction(level)) {
               return Optional.empty();
            }

            boolean hasNoConfiguration = nextSpawnData.getEntityToSpawn().size() == 1 && nextSpawnData.getEntityToSpawn().getString("id").isPresent();
            if (hasNoConfiguration) {
               mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.TRIAL_SPAWNER, null);
            }

            mob.setPersistenceRequired();
            nextSpawnData.getEquipment().ifPresent(mob::equip);
         }

         if (!level.tryAddFreshEntityWithPassengers(entity)) {
            return Optional.empty();
         }

         TrialSpawner.FlameParticle flameParticle = this.isOminous ? TrialSpawner.FlameParticle.OMINOUS : TrialSpawner.FlameParticle.NORMAL;
         level.levelEvent(3011, spawnerPos, flameParticle.encode());
         level.levelEvent(3012, spawnBlockPos, flameParticle.encode());
         level.gameEvent(entity, GameEvent.ENTITY_PLACE, spawnBlockPos);
         return Optional.of(entity.getUUID());
      }
   }
}
```

## trial_tick

出处：net.minecraft.world.level.block.entity.trialspawner.TrialSpawner / tickServer；returned_range={'start_line': 14, 'start_char': 0, 'end_line': 28, 'end_char': 0}；原输出truncated=False。

```java
public final class TrialSpawner {
   public void tickServer(final ServerLevel serverLevel, final BlockPos spawnerPos, final boolean isOminous) {
      this.isOminous = isOminous;
      TrialSpawnerState currentState = this.getState();
      if (this.data.currentMobs.removeIf(id -> shouldMobBeUntracked(serverLevel, spawnerPos, id))) {
         this.data.nextMobSpawnsAt = serverLevel.getGameTime() + this.activeConfig().ticksBetweenSpawn();
      }

      TrialSpawnerState nextState = currentState.tickAndGetNext(spawnerPos, this, serverLevel);
      if (nextState != currentState) {
         this.setState(serverLevel, nextState);
      }
   }
}
```

## trial_can_spawn

出处：net.minecraft.world.level.block.entity.trialspawner.TrialSpawner / canSpawnInLevel；returned_range={'start_line': 15, 'start_char': 0, 'end_line': 26, 'end_char': 0}；原输出truncated=False。

```java
public final class TrialSpawner {
   public boolean canSpawnInLevel(final ServerLevel level) {
      if (!level.getGameRules().get(GameRules.SPAWNER_BLOCKS_WORK)) {
         return false;
      } else if (this.overridePeacefulAndMobSpawnRule) {
         return true;
      } else {
         return level.getDifficulty() == Difficulty.PEACEFUL ? false : level.getGameRules().get(GameRules.SPAWN_MOBS);
      }
   }
}
```

## trial_reset_stats

出处：net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData / resetStatistics；returned_range={'start_line': 19, 'start_char': 0, 'end_line': 27, 'end_char': 0}；原输出truncated=False。

```java
public class TrialSpawnerStateData {
   public void resetStatistics() {
      this.detectedPlayers.clear();
      this.totalMobsSpawned = 0;
      this.nextMobSpawnsAt = 0L;
      this.cooldownEndsAt = 0L;
   }
}
```
