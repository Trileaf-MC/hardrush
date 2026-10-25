# 本次调查的原版证据

仅本次从目标 JAR 提取的方法和数据；未运行游戏。反编译器 Vineflower 1.12.0，各方法 CLI warnings 均为空。

JAR: `/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar`

JAR SHA-256: `a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6`（doctor 报告与服务端 bundle 内 SHA-256 一致）。

版本元数据：
```json
{
    "id": "26.3",
    "name": "26.3",
    "world_version": 5023,
    "series_id": "main",
    "protocol_version": 777,
    "pack_version": {
        "resource_major": 97,
        "resource_minor": 1,
        "data_major": 121,
        "data_minor": 0
    },
    "build_time": "2026-09-15T11:20:48+00:00",
    "java_component": "java-runtime-epsilon",
    "java_version": 25,
    "stable": true,
    "use_editor": false
}
```

## 伤害类型

原始路径：`data/minecraft/damage_type/mob_attack.json`

复核：`python3 scripts/mc-knowledge.py --version 26.3 data damage_type/mob_attack`

```json
{
  "exhaustion": 0.1,
  "message_id": "mob",
  "scaling": "when_caused_by_living_non_player"
}
```

## 属性：IronGolem.createAttributes

复核：`python3 scripts/mc-knowledge.py --version 26.3 source IronGolem --member createAttributes`

本次生成源文件：`.local/mc-knowledge/artifacts/a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6/sources/2eadb137f8f68cb422147b4554e4a7db842a188a7c29a9d3220de054777e8c71/source.java`

源文件 SHA-256：`71223359c2a3293d86aaf75ea0d725ebfff720ea3c988284187aa2d37f4bae40`

```java
package net.minecraft.world.entity.animal.golem;

import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder;

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

## 读取属性：IronGolem.getAttackDamage

复核：`python3 scripts/mc-knowledge.py --version 26.3 source IronGolem --member getAttackDamage`

本次生成源文件：`.local/mc-knowledge/artifacts/a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6/sources/d55102175248340d66c9fc37b97087734ddc85a2b5cba711760fd857e04cd8fc/source.java`

源文件 SHA-256：`6692e161a10a89d0ad3f5cabc14950abcef26fc9cd99d8b5be7a60d8a1dd2b9d`

```java
package net.minecraft.world.entity.animal.golem;

import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class IronGolem extends AbstractGolem implements NeutralMob {
   private float getAttackDamage() {
      return (float)this.getAttributeValue(Attributes.ATTACK_DAMAGE);
   }
}
```

## 随机伤害与调用顺序：IronGolem.doHurtTarget

复核：`python3 scripts/mc-knowledge.py --version 26.3 source IronGolem --member doHurtTarget`

本次生成源文件：`.local/mc-knowledge/artifacts/a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6/sources/67a4e59953dc7178f9fda7c94c6703c25a3493be5b339fc2d0a1a50a24be4c39/source.java`

源文件 SHA-256：`08e8c7a2f330604ffc887905f67fa3b68af9f3f72bcaeb2c8b8fa680af06888c`

```java
package net.minecraft.world.entity.animal.golem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

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

## 随机整数上界：BitRandomSource.nextInt

复核：`python3 scripts/mc-knowledge.py --version 26.3 source BitRandomSource --member nextInt --descriptor '(I)I'`

本次生成源文件：`.local/mc-knowledge/artifacts/a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6/sources/d6f75e770baeeb213455935d9452212d35abb4be919e002048e473d1bd304396/source.java`

源文件 SHA-256：`c9b39c88db5be6c85a49d1487b9cccbc4e789cb2099c0ed9890b2237c5062358`

```java
package net.minecraft.world.level.levelgen;

import net.minecraft.util.RandomSource;

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

## 缩放适用条件：DamageSource.scalesWithDifficulty

复核：`python3 scripts/mc-knowledge.py --version 26.3 source DamageSource --member scalesWithDifficulty`

本次生成源文件：`.local/mc-knowledge/artifacts/a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6/sources/4bbd21dbc045143cdfe108980a125df995711ba3ae39695a317eab35ee021a73/source.java`

源文件 SHA-256：`b179d754f733df04d1f5d2ecb39a9cf482b3a782bc61093a8cbb8450e8f4cf3d`

```java
package net.minecraft.world.damagesource;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

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

## 玩家难度缩放：Player.hurtServer

复核：`python3 scripts/mc-knowledge.py --version 26.3 source Player --member hurtServer`

本次生成源文件：`.local/mc-knowledge/artifacts/a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6/sources/28f91b735ba1e2b6ab14449224a99c3a34d9b83a328fd34f3d7a61471efaf1c8/source.java`

源文件 SHA-256：`57768e741a282383829196777a111139eb39470feecd4b51d37d78082fc72860`

```java
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
```

## 实际扣血：Player.actuallyHurt

复核：`python3 scripts/mc-knowledge.py --version 26.3 source Player --member actuallyHurt`

本次生成源文件：`.local/mc-knowledge/artifacts/a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6/sources/034a5f734c5afeee15116d35e5bc11546efd7a16ab010758285300de54cd64e2/source.java`

源文件 SHA-256：`f869b6f745f14d70fb3d517af232aa4cc2aa495f06c7334e71815ce41c15d58f`

```java
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
```
