**结论：Java Edition 26.3 中，普通骷髅持续处于细雪内，经过约 7 秒前置阶段，再经过约 15 秒转化阶段，成为流浪者。野生狼会主动攻击流浪者；已驯服的狼也保留这项主动攻击行为，但坐下等行为会影响实际追击。**

本次直接检查了 **26.3 正式版原版服务端 JAR**，本地服务端包的 SHA-1 与 [Mojang 26.3 下载元数据](https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json)一致。以下属于代码及字节码核查，未启动游戏客户端或服务器。

**1．骷髅在细雪中的完整转化过程**

| 阶段 | 过程 | 配置时间，按正常 20 TPS 换算 |
|---|---|---|
| 前置阶段 | 普通骷髅被判定处于细雪内，累计 `FreezingTime` | 阈值 140 tick，约 7 秒 |
| 转化阶段 | 开启转化状态，开始抖动，倒计时 `StrayConversionTime` | 初值 300 tick，约 15 秒 |
| 完成 | 创建流浪者，替换原骷髅 | 总体约 22 秒 |

这些参数来自 `Skeleton` 的 `ConversionTracker` 初始化；`isShaking()` 直接返回是否正在转化。[出处：Skeleton.java](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/Skeleton.java:18)

需要特别注意以下条件：

- **两个阶段都需要继续处于细雪内。**26.3 的代码每次更新都会检查这一条件；离开后，将前置计时置为 `-1`，并关闭转化状态。因此，即使已经抖动，离开细雪也会取消转化；重新进入须重新累计。[出处：ConversionTracker.tick()](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/ConversionTracker.java:53)
- **判定依据是 `isInPowderSnow`，没有额外要求头部浸没、下雪或寒冷生物群系。**细雪的方块效果设置这一标志；普通骷髅的 `canFreeze()` 返回 `false`，所以这套转化计时独立于通常的冻结伤害机制。[出处：InsideBlockEffectType.java](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/InsideBlockEffectType.java:7)、[Skeleton.canFreeze()](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/Skeleton.java:62)
- **转化后保留手持和穿戴装备；原本受伤的普通骷髅会变成满血流浪者。**实现是创建新实体并转移装备，没有复制旧实体的生命值；新生物构造时设为最大生命值。[出处：ConversionType.java](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/ConversionType.java:16)、[LivingEntity 构造器字节码](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/LivingEntity.constructor.bytecode.txt)

**精确计时边界：**“7＋15＝约 22 秒”适合作为机制说明，但不能保证严格第 440 tick 完成。前置计时从 `0` 开始需要 140 次合格更新，从离开细雪后的 `-1` 开始则需要 141 次；转化倒计时从 `300` 减至 **小于 0** 才完成，需要再经过 301 次合格更新。按该跟踪器计算，分别为 441 或 442 次合格更新。实体更新顺序还影响实际接触与开始计时的对应关系。[出处：计时方法字节码](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/ConversionTracker.tick.bytecode.txt)

上述秒数以正常 20 TPS、实体持续更新为前提；`NoAI` 会阻止该计时逻辑推进，区块停止实体更新时也不会按现实时间继续倒计时。

**2．野生狼与驯服狼会不会主动攻击流浪者？**

| 狼的状态 | 对流浪者的行为 |
|---|---|
| 自然生成、未驯服 | **会主动选择并攻击**，不需要先被流浪者或玩家攻击 |
| 已驯服、可正常行动 | **同样会主动选择并攻击**，不需要主人先攻击流浪者 |
| 已驯服、坐下行为正在生效 | 通常保持坐下，不主动追过去；选中目标与实际执行攻击是两回事 |

直接依据是 `Wolf.registerGoals()`：

```java
new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
```

这条规则针对 `AbstractSkeleton`，而流浪者声明为 `Stray extends AbstractSkeleton`，因此属于目标范围。**这条规则没有 `!isTame()` 限制**；狼捕猎羊、兔等动物所用的 `NonTameRandomTargetGoal` 才带有该限制。[出处：Wolf.registerGoals()](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/Wolf.registerGoals.java:16)、[Stray.java](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/Stray.java:21)、[NonTameRandomTargetGoal.java](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/NonTameRandomTargetGoal.java:17)

“会主动攻击”仍受目标搜索范围、视线、寻路和更高优先级行为影响，并非遇见后立即必定咬到。坐下目标的优先级高于跳扑、近战，并占用移动和跳跃控制，因此坐下生效时会压过追击动作；代码也存在水中、离地、主人受攻击等条件分支，不能把坐下概括为任何情况下都绝不行动。[出处：SitWhenOrderedToGoal.java](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/SitWhenOrderedToGoal.java:8)

**版本与证据边界：**以上结论限定为所核验的 **Java 26.3 原版实现**，不外推至基岩版、其他版本或模组环境。关键的离开细雪分支和狼的选敌注册已额外用字节码复核；实际画面与墙钟时间未作运行验证。完整哈希、类名、查询命令和本次保存的证据索引见 [provenance.json](/private/tmp/hardrush-mc-experiment-20261025/run-07/workspace/.local/evidence-26.3/provenance.json)。