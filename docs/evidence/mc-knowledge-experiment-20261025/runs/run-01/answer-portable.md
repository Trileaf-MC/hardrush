**Java 版 26.3 中，普通骷髅在细雪中约经过“7 秒准备＋15 秒抖动转化”后成为流浪者；野生狼和可自由行动的驯服狼，都会主动攻击流浪者。** 但计时边界、离开细雪和坐下指令需要分别说明。

本次核对的是 **26.3 正式版原版服务端 JAR**，不是相近版本。官方发布日期为 **2026 年 9 月 15 日**；本地 JAR 的校验值与 Mojang 官方下载元数据一致。[官方发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)；[本次 JAR 身份及校验记录](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/01-provenance.json)

**1. 骷髅在细雪中的完整转化过程**

| 阶段 | 具体行为 | 代码中的时间参数 |
|---|---|---|
| 接触细雪、积累时间 | 检查骷髅的 `isInPowderSnow` 标志，积累 `FreezingTime`；尚未进入转化抖动 | 门槛为 **140 tick**，通常称约 **7 秒** |
| 开始转化 | 达到门槛后，设置转化状态，骷髅开始抖动，初始化 `StrayConversionTime` | **300 tick**，通常称约 **15 秒** |
| 完成转化 | 倒计时减到 **小于 0** 时，将骷髅替换为流浪者；非静音实体触发转化音效 | 总体约 **22 秒** |

依据是 `Skeleton` 对 `ConversionTracker` 的参数设置、`isShaking()` 和跟踪器的 `tick()`；关键分支另用字节码核对过。[Skeleton 源码](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/02-Skeleton.java:20)；[计时及转换逻辑](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/03-ConversionTracker.java:53)

几个会影响实际结果的细节：

- **必须持续满足“在细雪中”的条件。** 在 26.3 中，一旦跟踪器检测到离开细雪，就把准备计时设为 `-1`，并取消转化状态。**即使已经开始抖动，也会取消；重新进入需要重新计时。**[对应分支](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/03-ConversionTracker.java:68)
- **严格计数并非恰好 440 tick。** 倒计时从 300 开始，需递减 **301 次**才小于 0。对于已在细雪外更新过、准备计时为 `-1` 的普通情况，准备阶段还需 **141 次**有效更新，总计 **442 次**，按 20 TPS 折合 **22.10 秒**。若准备计时从新建对象的初值 `0` 开始，则为 **441 次／22.05 秒**。这些数值以跟踪器读到细雪标志的更新为起点，不能直接当作从画面首次接触开始的精确秒表值。[完整计时源码](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/03-ConversionTracker.java:26)
- **转化与普通冻结伤害分开处理。** `Skeleton.canFreeze()` 返回 `false`；细雪仍会设置接触标志，因此不妨碍转化。皮革装备也没有被列为阻止转化的条件；关键仍是是否实际接触细雪。[骷髅冻结判定](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/02-Skeleton.java:63)；[细雪效果处理](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/04-InsideBlockEffectType.java:7)
- 转化会保留装备及其掉落概率，并复制自定义名称、状态效果等数据。计时只在服务端、实体存活且未设置 `NoAI` 时推进；暂停或停止实体更新不会按现实时间继续倒数。[转化条件](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/03-ConversionTracker.java:53)；[数据及装备复制](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/05-ConversionType.java:18)

**2. 野生狼与驯服狼是否主动攻击流浪者**

| 狼的状态 | 结论 |
|---|---|
| 自然生成、未驯服 | **会主动选择流浪者作为攻击目标**，不需要流浪者先攻击它 |
| 已驯服、可自由行动 | **同样会主动攻击**，不需要玩家先打流浪者或先被流浪者攻击 |
| 已驯服、正常执行坐下指令 | 通常会被坐下行为阻止追击；不能据此理解为所有受伤、涉水等情形下都绝不会行动 |

关键证据是 `Wolf.registerGoals()` 无条件注册了：

```java
new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
```

而 `Stray extends AbstractSkeleton`。这条目标没有“仅未驯服”的检查；狼针对部分动物使用的则是另一个有此限制的 `NonTameRandomTargetGoal`。所以，**驯服不会取消狼对流浪者的自动敌意**。[狼的目标注册](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/06-Wolf.registerGoals.java:16)；[流浪者继承关系](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/07-Stray.java:21)；[仅未驯服目标的检查](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/08-NonTameRandomTargetGoal.java:16)

“会主动攻击”仍以目标搜索、可攻击性、路径和 AI 优先级等条件满足为前提，并不保证一发现就立即咬到。坐下行为的优先级高于近战行为，会占用移动与跳跃控制。[坐下行为](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/sources/09-SitWhenOrderedToGoal.java:11)

以上是对 **Java 26.3 原版代码与关键字节码的静态调查**，未启动客户端或服务器。机制结论置信度高；画面抖动、音效出现时刻及从物理接触开始的逐 tick 表现未做运行验证，也不将这些结论直接外推到基岩版或其他 Java 版本。