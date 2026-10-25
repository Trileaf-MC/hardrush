# Java 26.3 原版静态核查记录

本文件是本次新生成的核查记录。未启动游戏客户端或服务器，未作实机测试。

## 一手资料与校验

- [Mojang 26.3 发布公告](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)，发布日期 2026-09-15。
- [官方版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)。
- [26.3 官方版本元数据](https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json)。下载原始响应的 SHA-1 与清单相符：`61fff1316bd50312624a858bbb5eb18fa45208ed`。
- [元数据指定的原版 client.jar](https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar)。下载大小 41,483,720 字节；SHA-1 实测与元数据相符：`e877b6a07acd633fb3bb475002175cec036e7b87`。
- JAR 内 `version.json` 声明 `id=26.3`、`stable=true`、`build_time=2026-09-15T11:20:48+00:00`。

## 转化逻辑

下列路径均相对于 JAR 的 `net/minecraft/world/entity/`。

- `monster/skeleton/Skeleton.<init>`：建立 `ConversionTracker`，等待标签 `FreezingTime`、阈值 140，转化标签 `StrayConversionTime`、初值 300。
- `Skeleton.lambda$new$2`：条件只有 `isInPowderSnow`；`lambda$new$0` 返回 `EntityTypes.STRAY`。
- `Skeleton.isShaking` 返回 `freezingTracker.isConverting()`。
- `Skeleton.tick` 先调用 tracker，再调用父类 tick。
- `Skeleton.canFreeze` 直接返回 false。因此普通骷髅不会受到正常细雪冰冻伤害；转化使用独立计数，不依赖 `TicksFrozen` 或皮革防冻检查。
- `InsideBlockEffectType.lambda$static$0` 先设置 `isInPowderSnow=true`，再检查 `canFreeze` 是否允许增加普通冻结计数。
- `PowderSnowBlock.getCollisionShape` 和 `canEntityWalkOnPowderSnow` 控制皮革靴站在雪面上的碰撞；实际未进入细雪则不会满足转化条件。

`ConversionTracker.tick` 的控制流（以下是根据字节码整理的伪代码，不是官方源码逐字转录）：

```java
if (!(mob.level() instanceof ServerLevel) || !mob.isAlive() || mob.isNoAi()) return;
if (isAfflicted.getAsBoolean()) { // Skeleton: isInPowderSnow
    if (isConverting()) {
        conversionTime--;
        if (conversionTime < 0) doConversion(level);
    } else {
        afflictionTime++;
        if (afflictionTime >= 140) startConversion(300);
    }
} else {
    afflictionTime = -1;
    setConverting(false);
}
```

关键跳转：字节码偏移 49 的 `ifeq` 跳到 116（离雪重置分支）；偏移 56 跳到 84（尚未开始转化时累加等待）；偏移 73 的 `ifge` 跳到 126（剩余计时非负则返回）。

因此两个阶段都要求持续满足入雪条件。抖动期间离雪也会取消；下一次开始抖动时重新设置 300，而不是续接上次剩余值。

计时边界：构造时 `afflictionTime=0`；检测到一次未入雪则变成 -1。初值 0 时，第 140 次有效入雪更新启动转化，第 441 次完成；初值 -1 时，第 141 次启动，第 442 次完成。第二阶段执行 301 次减法，300→299→…→0→-1 才完成。这是静态控制流的计数推导，未测量客户端画面或碰撞首刻。

`ConversionTracker.doConversion` 使用 `ConversionParams.single(mob, true, true)`。两个布尔参数分别为 `keepEquipment` 与 `preserveCanPickUpLoot`。`ConversionType$1.convert` 复制装备和装备掉落概率；`ConversionType.convertCommon` 及该单体转换实现均不复制原有当前生命值。新实体的 `LivingEntity` 构造函数以最大生命值初始化。因此普通受伤骷髅转化后是满血流浪者，持有和穿戴物保留。

## 狼的主动攻击

- `animal/wolf/Wolf.registerGoals`，字节码偏移 363–380，无条件注册优先级 7 的 `NearestAttackableTargetGoal(this, AbstractSkeleton.class, false)`。
- 这条注册路径没有 `isTame` 判断。`monster/skeleton/Stray` 的父类就是 `AbstractSkeleton`。
- 同一方法对普通动物和幼年海龟使用的是 `NonTameRandomTargetGoal`；其 `canUse` 才有 `!isTame()` 条件。因此不能把驯服狼不主动捕猎一般猎物的规则套到流浪者上。
- `NearestAttackableTargetGoal.canUse` 有随机扫描间隔及目标条件，故“主动攻击”不是保证每只狼立刻攻击所有附近流浪者。
- `Wolf.registerGoals` 同时注册优先级 2 的 `SitWhenOrderedToGoal` 和优先级 5 的 `MeleeAttackGoal`。正常坐下状态占用 MOVE/JUMP，压过追击。因此被命令坐下的驯服狼通常不会仅因附近出现流浪者而主动追击。
- 受伤反击、主人受伤和协助主人攻击另有相应目标规则，不宜把“坐下不主动追击”扩大成“任何情况下永远不会攻击”。

## 可复核的反汇编输出

本目录的 `*.class.txt` 由 `disassemble.py` 使用 Python 的 jawa 库静态读取该 JAR 生成。每个方法保留字节码偏移、操作码和解析后的常量引用；带 `->` 的跳转已换算为方法内绝对偏移。

- `Skeleton.class.txt`
- `ConversionTracker.class.txt`
- `ConversionParams.class.txt`
- `ConversionType.class.txt` 与 `ConversionType$1.class.txt`
- `LivingEntity.class.txt`
- `InsideBlockEffectType.class.txt` 与 `PowderSnowBlock.class.txt`
- `Wolf.class.txt`、`Stray.class.txt`
- `NearestAttackableTargetGoal.class.txt`、`NonTameRandomTargetGoal.class.txt`
- `SitWhenOrderedToGoal.class.txt`

结论适用于上述校验匹配的 Java 26.3 原版文件。不以滚动更新的 Wiki、旧版本记忆、模组、插件、基岩版或网络个案替代该版本代码。秒数按 20 TPS 换算；暂停、实体停止更新、卡顿等会使现实等待时间变化。
