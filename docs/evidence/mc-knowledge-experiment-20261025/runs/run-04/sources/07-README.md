# Minecraft Java 26.3 原版静态调查证据

本目录为本次新生成的资料。未运行游戏客户端或服务器。反编译器为本机 Vineflower 1.12.0，使用 OpenJDK 27 执行。

## 版本与来源

- 官方版本清单：https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- 26.3 官方元数据：https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json
- 官方客户端：https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar
- 下载文件：`official-client-26.3.jar`。下载后计算 SHA-1，结果为 `e877b6a07acd633fb3bb475002175cec036e7b87`，与官方元数据相符。
- 缓存文件：`/Users/leifwebber/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar`。
- `version.json` 标明 `id=26.3`、`stable=true`、构建时间 `2026-09-15T11:20:48+00:00`。
- `class-verification.json` 记录抽取的 41 个缓存类与官方客户端的逐字节比较；40 个一致。唯一不同的是客户端 `AbstractSkeletonRenderer`，已另从官方 JAR 反编译到 `official-sources/AbstractSkeletonRenderer.java`。本文机制结论涉及的 Skeleton、ConversionTracker、Stray、Wolf、各 AI goal、Mob、LivingEntity、ConversionType、PowderSnowBlock 均逐字节一致。
- `sources/InsideBlockEffectType.java` 与 `resources/` 的标签直接来自已验证的官方客户端。

## 关键代码入口

- `sources/net/minecraft/world/entity/monster/skeleton/Skeleton.java` 第 20–22 行：细雪条件、140 tick 前置阈值、300 tick 转化初值、目标流浪者、事件 1048；第 35–42 行：发抖及更新；第 63–64 行：免冻伤。
- `sources/ConversionTracker.java` 第 53–71 行：服务端、存活、AI 开启时更新；细雪条件包围两个阶段；离开后前置计时置 -1 并取消转化。第 97–106 行：转换、保留装备与拾取能力、非静音时触发声音事件。
- `ConversionTracker.javap.txt`：同一计时器的 javap 字节码，独立核对递增、递减及比较边界。
- `sources/InsideBlockEffectType.java` 第 7–11 行：先设置处于细雪的标志，再单独判断普通冻结免疫；两者不同。
- `sources/net/minecraft/world/level/block/PowderSnowBlock.java` 第 96–97 行：应用细雪效果；第 139–144 行：皮靴使实体能走在细雪表面。
- `sources/net/minecraft/world/entity/ConversionType.java` 第 39–46 行：保留装备和装备掉落概率；第 94 行起：保留状态效果等数据，不复制原生命值。
- `sources/net/minecraft/world/entity/Mob.java` 第 1230–1255 行：创建新实体并移除原实体；`LivingEntity.java` 第 295–298 行：新实体以最大生命值初始化。
- `sources/net/minecraft/world/entity/animal/wolf/Wolf.java` 第 130–150 行：AI 注册；第 149 行对 AbstractSkeleton 使用普通 NearestAttackableTargetGoal，没有未驯服限制。第 508–514 行：成功驯服后自动命令坐下；第 400–407 行：受伤时解除坐下命令。
- `sources/net/minecraft/world/entity/monster/skeleton/Stray.java` 第 21 行：Stray 继承 AbstractSkeleton。
- `sources/net/minecraft/world/entity/ai/goal/target/NonTameRandomTargetGoal.java` 第 17–19 行：只有这一目标选择器显式要求未驯服，狼将它用于动物猎物和幼龟，没有用于骷髅类。
- `sources/net/minecraft/world/entity/ai/goal/SitWhenOrderedToGoal.java`：坐下目标控制 MOVE/JUMP；其优先级为 2，高于狼的扑跃 4 和近战攻击 5。坐下通常抑制主动追击，但并非永久无条件禁止战斗。
- `sources/net/minecraft/world/entity/ai/goal/target/OwnerHurtByTargetGoal.java` 与 `OwnerHurtTargetGoal.java`：未被命令坐下的驯服狼可以保护主人、攻击主人攻击过的对象。

## 时间的精确定义

通俗描述为约 7 秒预备，再约 15 秒发抖转化，总计约 22 秒，按 20 TPS 换算。

按代码逐次更新：前置每次加 1，达到 140 时开启转化，并设置 conversionTime=300；开启的同一次更新不会递减它。之后每次先减 1，只有小于 0 才转化，所以第二阶段需 301 次有效更新。

对已经在细雪外完成至少一次有效更新的普通骷髅，afflictionTime=-1；再次进入后需 141 次细雪判定为真的更新达到 140，再需 301 次，合计 442 次有效更新。若初值尚为构造默认值 0，则是 140+301=441 次。这是静态代码计数；不要把从视觉上刚碰到细雪开始的墙钟时间宣称为精确 22.10 秒。碰撞标记的采样时点、tick 对齐、TPS 与显示延迟都会影响观测。

任何阶段判定不在细雪中都会重置前置计时并取消转化，不能分段累计；再次进入重新开始。

## 复核方法

对官方 JAR 直接执行（JAVA_BIN 指向可用的 JDK 25 或更新版本）：

```sh
"$JAVA_BIN"/javap -c -p -classpath official-client-26.3.jar net.minecraft.world.entity.ConversionTracker
"$JAVA_BIN"/javap -c -p -classpath official-client-26.3.jar net.minecraft.world.entity.monster.skeleton.Skeleton
"$JAVA_BIN"/javap -c -p -classpath official-client-26.3.jar net.minecraft.world.entity.animal.wolf.Wolf
"$JAVA_BIN"/javap -p -classpath official-client-26.3.jar net.minecraft.world.entity.monster.skeleton.Stray
```

`JAVA_BIN` 此处是包含 `java`、`javap` 的 bin 目录路径。也可用 Vineflower 对 `classes/` 反编译，并以官方 JAR 作为外部类型依赖。

Wiki 的 https://minecraft.wiki/w/Stray#Converting 提供约 7+15 秒概述；https://minecraft.wiki/w/Wolf#Behavior 提供野狼及站立驯服狼主动攻击骷髅变种的概述。本次网页正文直接抓取失败，搜索索引返回了相关段落，因此精确的 26.3 结论以已核验的官方二进制代码为依据。
