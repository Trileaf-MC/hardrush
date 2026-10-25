**在 Java Edition 26.3 原版中，骷髅转化为流浪者需要约 22 秒，期间必须持续处于细雪中；野生狼和已驯服的狼都具备主动攻击流浪者的 AI。**

版本依据是本地 **26.3 原版服务端 JAR**，工具校验其 SHA-256 与服务端 bundle 记录一致；官网也有对应的 [Java 26.3 正式版公告](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。以下属于源码及关键字节码核查，未启动游戏进行实测。

**1．骷髅在细雪中的完整转化过程**

| 阶段 | 条件与行为 | 配置时间 |
|---|---|---|
| 前置阶段 | 骷髅被判定处于细雪中，累积独立的转化计时；此时尚未因转化而抖动 | 140 tick，约 7 秒 |
| 转化阶段 | 开始抖动，转化倒计时设为 300；仍须处于细雪中 | 约 15 秒 |
| 完成 | 倒计时减至负数，转化为流浪者；保留装备，并在非静音时触发转化音效 | 总计约 22 秒 |

直接依据：`Skeleton` 创建计时器时传入 `140`、`300`，条件为 `isInPowderSnow`；`isShaking()` 返回是否正在转化。实际计时与完成逻辑在 `ConversionTracker`。[Skeleton 源码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/Skeleton.java:20)、[ConversionTracker 源码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/ConversionTracker.java:53)

需要特别注意：

- **任何阶段离开细雪，都会取消并重置进度。** 包括已经开始抖动之后；重新进入要从前置阶段开始。代码在细雪条件不成立时，将前置计时设为 `-1`，并清除转化状态。此分支已用字节码交叉核对。[取消逻辑](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/ConversionTracker.java:68)
- **它不是靠冻伤完成转化。** `Skeleton.canFreeze()` 返回 `false`；细雪仍会设置“处于细雪中”的标志，供独立转化计时器使用。因此，皮革护甲的防冻作用本身不能阻止转化；但皮革靴若让它站在细雪表面、没有进入细雪判定，则不会开始计时。[骷髅防冻实现](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/Skeleton.java:63)、[细雪标志与冻结处理](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/InsideBlockEffectType.java:7)
- 计时要求实体存活、AI 未被关闭，并正常执行服务端 tick；暂停或实体不再被模拟时，不能按现实时间继续累计。[计时入口条件](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/ConversionTracker.java:54)

**精确到 tick 时，不宜写成“恰好 440 tick”。** 前置计数先加一再检查 `>=140`；第二阶段从 `300` 逐次减一，直到 `<0` 才完成，需要后续 **301 次**有效更新。前置计数从 `0` 开始时合计 441 次，从离开细雪后重置的 `-1` 开始时合计 442 次。按 20 TPS 折算约 **22.05～22.10 秒**；实体接触细雪与计时读取的先后顺序，还会影响从“进入瞬间”测量的边界。因此实际描述建议使用“**持续约 22 秒**”。[计时实现](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/ConversionTracker.java:53)

**2．野生狼和驯服狼是否主动攻击流浪者**

| 狼的状态 | 结论 |
|---|---|
| 自然生成、未驯服 | **会主动选择流浪者为攻击目标**，不需要先被流浪者攻击 |
| 已被玩家驯服，正常活动 | **同样会主动选择流浪者为攻击目标**，不需要主人先动手 |
| 已被命令坐下 | 坐下行为通常阻止追击；底层的骷髅类索敌规则仍然存在 |

关键证据是 `Wolf.registerGoals()` 注册了：

```java
new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
```

这条规则**没有 `!isTame()` 限制**。而流浪者 `Stray` 继承自 `AbstractSkeleton`，所以野生狼和驯服狼都适用；代码也没有按流浪者的自然生成或转化来源作区分。[狼的目标规则](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/Wolf.registerGoals.java:50)、[流浪者继承关系](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/Stray.java:21)

“会主动攻击”表示存在主动索敌与攻击行为，并不保证看见后立即咬到：发现目标仍受搜索范围、视线及敌我关系筛选，实际追击还受路径和其他更高优先级行为影响。坐下目标的优先级为 2，高于扑跃的 4 和近战的 5，并占用移动与跳跃控制。[目标筛选](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/TargetingConditions.java:63)、[坐下行为](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/SitWhenOrderedToGoal.java:11)

以上结论限定于 **Java 26.3 原版实现**，不外推到基岩版、其他版本或模组服务端。用于复核的源码副本、原始证据路径及完整 JAR 哈希保存在 [证据来源清单](/private/tmp/hardrush-mc-optimization-20261025/runs/run-18/attempt-01/workspace/.local/vanilla-26.3-skeleton-wolf-evidence/provenance.json)。