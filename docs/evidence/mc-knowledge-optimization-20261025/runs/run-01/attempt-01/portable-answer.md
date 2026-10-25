**在 Java 26.3 原版中，骷髅在细雪内约需 22 秒转化为流浪者；未驯服和已驯服的狼都具有主动攻击流浪者的 AI。** 已驯服狼是否实际追咬，还受坐下状态、寻路和其他行为影响。

以下依据本地 **26.3 原版服务端 JAR** 静态检查；关键计时与狼的选敌注册另用字节码核对。版本校验、JAR SHA-256 和查询记录保存在[证据索引](sources/.local/vanilla-26.3-evidence/provenance.json)。公开版本信息可对照[官方 26.3 发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。

**对于骷髅转化，完整过程如下。** 秒数按正常 20 TPS 换算。

| 阶段 | 行为 | 时间 |
|---|---|---|
| 接触细雪 | 细雪效果将实体的 `isInPowderSnow` 标记设为真，转化逻辑据此计时 | 不要求先受到冰冻伤害 |
| 前置阶段 | 累加 `FreezingTime`，达到阈值后启动转化 | 阈值 **140 tick，约 7 秒** |
| 转化阶段 | 骷髅开始颤抖，`StrayConversionTime` 从 300 递减；此时仍是骷髅 | **约 15 秒** |
| 完成 | 创建流浪者并移除原骷髅；非静音实体触发转化音效 | 合计通常称为 **约 22 秒** |

这些参数直接写在 [`Skeleton` 的转化跟踪器初始化中](sources/.local/vanilla-26.3-evidence/Skeleton.java:20)，阶段推进见 [`ConversionTracker.tick()`](sources/.local/vanilla-26.3-evidence/ConversionTracker.java:53)。

有几个会改变结果的重要条件：

- **两个阶段都必须持续被判定处于细雪中。** 一旦某次有效更新检测到已经离开细雪，就将前置计数设为 `-1`，并取消转化标记；即使已经颤抖，也不能离开后继续完成。重新进入需要重新走完整流程。依据同一个 `tick()` 的退出分支。
- **严格计时并非恰好 440 tick。** 转化倒计时判断是“先减一，再检查 `< 0`”，因此从 300 开始需要 **301 次有效更新**；前置计数从初始 `0` 或离开细雪后的 `-1` 开始，分别需要 140 或 141 次。因此连续满足条件时，整个流程需要 **441 或 442 次有效更新，约 22.05～22.10 秒**。这是代码计数，实际从接触瞬间计时还受 tick 对齐影响。
- **骷髅不会因普通细雪冰冻而受伤。** `Skeleton.canFreeze()` 固定返回 `false`，但细雪仍会设置接触标记，因此转化照常进行；皮革装备也不会直接阻止这条转化逻辑。见 [`canFreeze()`](sources/.local/vanilla-26.3-evidence/Skeleton.java:63) 与[细雪效果实现](sources/.local/vanilla-26.3-evidence/InsideBlockEffectType.java:7)。
- 转化保留装备及对应掉落概率，并复制自定义名称、已有状态效果等。见 [`ConversionType.SINGLE`](sources/.local/vanilla-26.3-evidence/ConversionType.java:16)。实体必须存活、AI 未关闭，且持续获得服务端更新；未更新或 TPS 降低时，现实等待时间会延长。

**对于狼，驯服不会移除它对流浪者的主动选敌规则。**

| 狼的状态 | 会怎样 |
|---|---|
| 自然生成、未驯服 | **会主动选择流浪者为攻击目标**，不需要先被流浪者攻击 |
| 已驯服，正常站立、可行动 | **同样会主动选择并追咬流浪者**，不需要主人先攻击 |
| 已驯服，坐下行为正在生效 | 通常不会起身追咬；坐下限制移动和跳跃，但没有移除骷髅类选敌规则 |

关键证据是 `Wolf.registerGoals()` 无条件注册了：

```java
new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
```

流浪者的声明是 `Stray extends AbstractSkeleton`，因此包含在目标范围内。这里使用普通选敌 Goal，没有 `!isTame()` 限制。见[狼的注册代码](sources/.local/vanilla-26.3-evidence/Wolf.registerGoals.java:22)、[对应字节码](sources/.local/vanilla-26.3-evidence/Wolf.registerGoals.bytecode.txt)及[流浪者继承关系](sources/.local/vanilla-26.3-evidence/Stray.java:21)。

“会主动攻击”表示具备这条 AI，**不保证每次相遇都立即咬到**：搜索范围、有效目标条件、其他更高优先级行为和寻路都会影响执行；实际近战还要求攻击冷却结束、目标进入近战范围且有视线。见[坐下行为](sources/.local/vanilla-26.3-evidence/SitWhenOrderedToGoal.java:13)与[近战判定](sources/.local/vanilla-26.3-evidence/MeleeAttackGoal.canPerformAttack.java:5)。

上述结论限定于所核验的 **Java 26.3 原版制品**，未进行游戏运行时验证，也不外推到基岩版或其他版本。