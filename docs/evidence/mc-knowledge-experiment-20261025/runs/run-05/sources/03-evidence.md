# Java Edition 26.3 原版机制：本次静态调查证据

本文件是本次调查新生成的证据索引。没有读取既有调查报告、聊天历史、个人记忆或其他实验目录；没有使用子代理，没有启动游戏客户端或服务器。所有 JAR 仅作为 ZIP / class 文件读取。

## 版本与下载来源

- 官方发布公告：https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3
- 官方版本清单：https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- 26.3 元数据：https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json
- 原版 server.jar：https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar
- 元数据声明及实测 server.jar SHA-1 均为 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`，大小 62,294,556 字节。
- 内层 JAR 位于 `META-INF/versions/26.3/server-26.3.jar`，本地命名为 `server-inner.jar`。
- 内层 version.json：id/name `26.3`，stable `true`，data version `5023`，protocol `777`，build_time `2026-09-15T11:20:48+00:00`。原文见 `inner-version.json`。
- class 文件 SHA-256 见 `class-hashes.json`。

## 骷髅转化的直接证据

类：`net.minecraft.world.entity.monster.skeleton.Skeleton`

构造函数建立 `ConversionTracker`，参数包括：

- 输出实体：`EntityTypes.STRAY`；
- 条件：`this.isInPowderSnow`，来自 `lambda$new$2()`；
- 前段存储字段：`FreezingTime`，阈值 `140`；
- 后段存储字段：`StrayConversionTime`，初始值 `300`；
- 完成事件：`1048`。

`isShaking()` 直接返回 tracker 的 `isConverting()`。
`tick()` 先调用 tracker.tick，再调用父类 tick。
`canFreeze()` 无条件返回 false。

完整反汇编见 `Skeleton.bytecode.txt`。

类：`net.minecraft.world.entity.ConversionTracker`

以下是依据 `tick()` 字节码转写的控制流伪代码，并非 Mojang 公布的 Java 源文件：

```java
// 构造时 afflictionTime = 0。
if (mob.level() instanceof ServerLevel && mob.isAlive() && !mob.isNoAi()) {
    if (isAfflicted.getAsBoolean()) { // Skeleton: isInPowderSnow
        if (isConverting()) {
            --conversionTime;
            if (conversionTime < 0) {
                doConversion(serverLevel);
            }
        } else {
            ++afflictionTime;
            if (afflictionTime >= totalAfflictionTime /* 140 */) {
                startConversion(totalConversionTime /* 300 */);
            }
        }
    } else {
        afflictionTime = -1;
        setConverting(false);
    }
}
```

`startConversion(int)` 设置 conversionTime 并将 converting 标志设为 true。
`doConversion` 使用 `ConversionParams.single(mob, true, true)` 创建流浪者；两个 true 分别为保留装备和保留可拾取物品状态。

由此可见：

1. 约 7 秒预备，然后约 15 秒发抖转化，总体约 22 秒（按正常 20 TPS 换算）。
2. 后段同样位于 isInPowderSnow 条件内部。离雪不仅停止后段计时，还会取消 converting 并把前段设回 -1。重新入雪必须重新经历两个阶段。
3. 若此前已在细雪外处理过 tick，前段从 -1 累加到 140，需要 141 次有效处理；后段从 300 递减到小于 0，需要 301 次。合计通常 442 次有效处理，20 TPS 下约 22.10 秒。
4. 若构造后的 0 尚未被外界 tick 重置，就开始连续满足条件，则前段 140 次、总计 441 次。以上是函数调用计数，不承诺从肉眼接触细雪起的精确 tick 编号；碰撞标志更新、暂停、实体是否被 tick 和 TPS 均影响观察。
5. 这不是通过冻伤扣血触发的机制。Skeleton.canFreeze=false；皮革防冻判定不在转化条件中。若实际进入细雪，皮革装备不会免除此转化；皮革靴使实体站在细雪上方的情况应另行区分。

完整反汇编见 `ConversionTracker.bytecode.txt`，其中 tick 方法的字节码偏移 40–123 覆盖关键分支。

## 转化结果：装备与生命值

- `Mob.convertTo`：创建新的目标实体、执行转化复制步骤、加入世界并移除原实体。
- `ConversionType$1.convert`：keepEquipment 为 true 时复制各装备槽的物品及装备掉落概率。
- `ConversionType.convertCommon`：复制药水效果等共同状态，但不复制当前生命值。
- `LivingEntity` 构造函数：`setHealth(getMaxHealth())`。
- 因而在普通原版条件下，受伤骷髅转化出的流浪者从满血开始，而非继承原来的残血；同一 tick 后续其他伤害是另一个过程。
- 原版 `data/minecraft/tags/entity_type/freeze_immune_entity_types.json` 包含 `minecraft:stray`，所以流浪者免疫普通细雪冻结伤害。标签原文另存为同名 JSON。

原始反汇编分别见 `Mob.bytecode.txt`、`ConversionType$1.bytecode.txt`、`ConversionType.bytecode.txt`、`LivingEntity.bytecode.txt`。

## 狼主动攻击流浪者的直接证据

类：`net.minecraft.world.entity.animal.wolf.Wolf`

`registerGoals()` 为所有狼无条件注册优先级 7 的目标规则，语义为：

```java
targetSelector.addGoal(7,
    new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false));
```

`Stray` 直接继承 `AbstractSkeleton`，因此匹配此目标类型，天然生成和转化产生的流浪者均适用。

这条规则没有 `isTame()` 限制，也没有要求狼或主人先被攻击。Wolf 中捕食其他动物使用的规则则是 `NonTameRandomTargetGoal`，后者 `canUse()` 明确检查 `!isTame()`；两条规则应分别理解。

所以：正常自由行动的野狼会主动选取、追逐并攻击流浪者；正常站立且可行动的驯服狼也会。目标发现、其他更高优先级行为、寻路及实际攻击距离仍影响是否当场咬到。

## 坐下、反击和护主

- `Wolf.registerGoals()`：坐下行为优先级 2，近战行为优先级 5；坐下行为控制 MOVE/JUMP，正常坐下会阻止主动追击。
- `SitWhenOrderedToGoal.canUse()`：检查命令、驯服状态、是否在水中、是否着地、主人是否存在等条件。所以这里的“坐下不主动追击”指通常能执行坐下行为的状态，不是对所有特殊环境的绝对保证。
- `OwnerHurtByTargetGoal.canUse()`、`OwnerHurtTargetGoal.canUse()`：要求已驯服且没有被命令坐下，随后分别根据主人受击及主人攻击的记录选择目标。
- `Wolf.hurtServer()`：通过无敌检查后，先 `setOrderedToSit(false)`，再处理伤害。所以坐下不是遭到攻击时永远不反击的保证。

完整反汇编见 `Wolf.bytecode.txt`、`SitWhenOrderedToGoal.bytecode.txt`、`OwnerHurtByTargetGoal.bytecode.txt`、`OwnerHurtTargetGoal.bytecode.txt`。

## 复核方式与证据边界

下载上面的官方 server.jar 并校验 SHA-1；用 ZIP 工具提取内层 JAR。安装有适当 JDK（例如 25）的环境可使用：

```sh
javap -c -p -classpath server-inner.jar net.minecraft.world.entity.monster.skeleton.Skeleton
javap -c -p -classpath server-inner.jar net.minecraft.world.entity.ConversionTracker
javap -c -p -classpath server-inner.jar net.minecraft.world.entity.animal.wolf.Wolf
javap -c -p -classpath server-inner.jar net.minecraft.world.entity.monster.skeleton.Stray
```

这些命令只是反汇编，不启动游戏。本次使用本目录的 `disassemble.py` 和 jawa 2.2.0 直接解析 class，未使用 Java 运行时执行游戏。

历史辅助出处：https://www.minecraft.net/en-us/article/minecraft-snapshot-21w13a ，其中明确区分“7 秒开始转化”和“已经转化完成”。该历史公告本身不足以证明 26.3 的离雪行为；本调查以 26.3 二进制为依据。

确定性：条件分支、两个计时参数、狼的目标类别和驯服状态处理来自指定正式版的直接静态证据。逐 tick 计数是从控制流推导的结果；未进行实际游戏测时，不把推导写成实测结果。结论不外推到基岩版、其他版本、模组或插件环境。
