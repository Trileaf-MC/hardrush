针对 **Minecraft Java Edition 26.3 正式版原版**：骷髅在细雪中经过“预备冻结→颤抖转化→变成流浪者”三个阶段，通常概括为 **约 7＋15＝22 秒**；**野生狼和站立状态的驯服狼都会主动攻击流浪者**。

本次以本机 26.3 原版服务端 JAR 的 Vineflower 反编译结果为主，并用 `javap` 核对关键字节码。

**1．骷髅在细雪中的完整转化过程**

| 阶段 | 行为与时间 |
|---|---|
| 接触细雪、积累预备时间 | 代码检测 `isInPowderSnow`，预备计数阈值为 **140 游戏刻，约 7 秒**。不要求眼睛或整个头部浸没。 |
| 开始转化 | 达到阈值后，转化状态开启，骷髅开始颤抖；转化倒计时设为 **300 游戏刻，通常称为 15 秒**。 |
| 完成转化 | 倒计时结束后，原骷髅被新的流浪者替换；手持物品和穿戴装备保留，新的流浪者以满血出现，即通常的 **20 点生命值（10 颗心）**。 |

参数和颤抖状态见 [Skeleton.java](/private/tmp/hardrush-mc-experiment-20261025/run-03/workspace/evidence-26.3/decompiled/net/minecraft/world/entity/monster/skeleton/Skeleton.java:18)；阶段推进见 [ConversionTracker.java](/private/tmp/hardrush-mc-experiment-20261025/run-03/workspace/evidence-26.3/decompiled/net/minecraft/world/entity/ConversionTracker.java:53)。[Wiki 的流浪者转化说明](https://minecraft.wiki/w/Stray#Converting)也记载了“7 秒后颤抖，再过 15 秒转化”、满血和装备保留。

有几个影响实际操作的细节：

- **两个计时阶段都必须保持细雪接触。**26.3 中，只要转化跟踪器读到“不在细雪中”，就会取消转化并将预备计数重置为 `-1`；即使已经开始颤抖，离开后也不会继续完成，重新进入需要重新计时。依据是上述 `ConversionTracker.tick()` 的第 67—69 行。
- **这是独立的转化机制，不是普通冻伤。**`Skeleton.canFreeze()` 固定返回 `false`，因此普通骷髅不会被细雪冻伤；转化也不依赖普通冻结值。皮革护甲不会直接阻止转化。不过皮革靴能让它站在细雪表面，可能使其根本没有进入细雪接触状态。参见 [细雪效果代码](/private/tmp/hardrush-mc-experiment-20261025/run-03/workspace/evidence-26.3/decompiled/net/minecraft/world/entity/InsideBlockEffectType.java:6)和 [细雪碰撞、皮革靴判定](/private/tmp/hardrush-mc-experiment-20261025/run-03/workspace/evidence-26.3/decompiled/net/minecraft/world/level/block/PowderSnowBlock.java:123)。
- 只有实体存活、AI 未禁用且正在接受服务端 tick 时才推进计时。秒数按 **20 TPS** 换算；卡顿或实体停止 ticking 会延长现实等待时间。

**精确计时不能直接写成“恰好 440 tick”。**26.3 的第二阶段先递减，再判断 `<0`，所以从 `300` 开始实际需要 **301 次后续递减**。第一阶段从初始值 `0` 开始需要 140 次调用；若此前在细雪外被重置为 `-1`，则需要 141 次。因此，连续满足细雪条件的跟踪器调用合计是 **441 或 442 次**，约 **22.05／22.10 秒**。这是静态代码计数，不能等同于从碰撞瞬间到画面变化的精确实测时间。

**2．狼是否主动攻击流浪者？**

| 狼的状态 | 无需挑衅，会主动攻击流浪者吗？ |
|---|---|
| 自然生成、未驯服 | **会。**无需玩家先攻击，也无需流浪者先伤害狼。 |
| 已驯服、正常站立活动 | **也会。**无需主人先攻击或受到攻击。 |
| 已被命令坐下并保持坐姿 | **通常不会主动起身追击。**坐下行为会阻止追击所需的移动和跳跃。 |

直接证据是 [Wolf.registerGoals()](/private/tmp/hardrush-mc-experiment-20261025/run-03/workspace/evidence-26.3/decompiled/net/minecraft/world/entity/animal/wolf/Wolf.java:130)：

```java
new NearestAttackableTargetGoal(this, AbstractSkeleton.class, false)
```

该目标选择规则**没有“必须未驯服”的条件**，而 [Stray 类继承 AbstractSkeleton](/private/tmp/hardrush-mc-experiment-20261025/run-03/workspace/evidence-26.3/decompiled/net/minecraft/world/entity/monster/skeleton/Stray.java:21)，所以流浪者在其主动攻击目标范围内。相比之下，狼捕猎普通动物使用的另一套目标规则才带有未驯服限制。[Wiki 狼的行为说明](https://minecraft.wiki/w/Wolf#Behavior)也明确记载：站立的驯服狼会无须挑衅地攻击骷髅及其变种。

这里的“会主动攻击”仍受目标搜索、其他高优先级行为和实际可接近性影响，并不保证看到后立刻咬到。坐下也不是绝对免战状态：狼自身受到有效攻击时，代码会取消坐下命令。

**版本与证据边界：**上述细节限定于 **26.3 正式版**，不外推至基岩版或其他版本。本机外层服务端 JAR 的 SHA-1 与[官方 26.3 发行页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)所链接下载的摘要一致。此次未启动游戏客户端或服务器，因此逐 tick 数字属于代码推导；复核路径、摘要、反编译文件和字节码已保存在[核查材料说明](/private/tmp/hardrush-mc-experiment-20261025/run-03/workspace/evidence-26.3/README.md)。