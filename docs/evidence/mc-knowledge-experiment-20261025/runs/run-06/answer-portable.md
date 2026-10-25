**Java 26.3 原版中，骷髅转化为流浪者需要约 22 秒，分为两个阶段；两个阶段都要求持续处于细雪中。野生狼和已驯服的狼都会主动选择流浪者作为攻击目标，但坐下、寻路及其他 AI 行为会影响实际追击。**

本次核查对象是 **Java Edition 26.3 正式版**。本地服务端包的 SHA-1 与 Mojang 官方版本清单相符，内部原版 JAR 的 SHA-256 也通过校验，记录见[来源与校验信息](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/01-provenance.json)。以下属于源码及字节码静态调查，未启动游戏客户端或服务器。[官方版本说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)

**1．骷髅在细雪中转化的完整过程**

| 阶段 | 行为 | 代码中的时间参数 |
|---|---|---|
| 前置累计 | 普通骷髅被判定处于细雪中，累计 `FreezingTime` | 阈值 **140 tick**，名义约 **7 秒** |
| 转化倒计时 | 达到阈值后开始发抖，启动 `StrayConversionTime` | 设为 **300 tick**，名义约 **15 秒** |
| 完成 | 原骷髅被替换为流浪者 | 合计通常概括为 **约 22 秒** |

这些参数直接来自 26.3 的 [Skeleton 初始化代码](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/02-Skeleton.java:20)。

需要补充几个影响实际行为的细节：

- **离开细雪会取消，包括已经开始发抖的阶段。**只要一次有效转化检查读到 `isInPowderSnow=false`，前置计数便重置为 `-1`，转化状态关闭；重新进入后须重新经历前置累计和倒计时。
- **判定依据是“处于细雪”的实体标记。**转化检查不要求特定生物群系、天气或头部浸没，也不以受到冻结伤害为条件。普通骷髅的 `canFreeze()` 返回 `false`，不会先靠冻伤来触发转化。
- 计时只在服务端、实体存活且未禁用 AI 时推进；实体没有获得更新时，不会按现实时间继续倒数。

上述控制流程见 [ConversionTracker.tick()](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/03-ConversionTracker.java:53)，细雪标记的设置见 [InsideBlockEffectType](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/04-InsideBlockEffectType.java:7)。

**严格到 tick 时，不能将“140＋300”当作恰好 440 次更新。**代码先递减倒计时，再检查是否 **小于 0**，因此从 300 开始需要 **301 次后续有效更新**。通常骷髅已在细雪外更新过，前置计数是 `-1`，还需要 **141 次**持续判定在细雪中的更新才启动转化；合计 **442 次有效检查，按 20 TPS 约 22.1 秒**。若前置计数仍是初始化值 `0`，则为 441 次。这里是根据代码推算，实际接触细雪到观察到变身还受检测时序和服务器 TPS 影响。比较依据已用[原始字节码](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/05-ConversionTracker.tick.bytecode.txt:1)复核。

转化后，**原骷髅手持和穿戴的装备保留；新流浪者以满生命值创建，不继承原骷髅损失的生命值**。依据是[装备转移代码](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/06-ConversionType.java:40)、[新实体创建流程](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/07-Mob.convertTo.java:9)及[生命值初始化字节码](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/08-LivingEntity.constructor.bytecode.txt:76)。

**2．野生狼与已驯服的狼，会主动攻击流浪者吗？**

| 狼的状态 | 26.3 原版行为 |
|---|---|
| 自然生成、未驯服 | **会主动选择流浪者作为攻击目标**，无需流浪者先攻击 |
| 已驯服、正常站立活动 | **同样会主动选择流浪者**，无需主人先攻击或先受伤 |
| 已驯服、被命令坐下 | 坐下行为正常生效时，会阻止移动和跳跃，通常不会主动追过去攻击 |

关键证据是 `Wolf.registerGoals()` 中注册的规则：

```java
new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
```

流浪者 `Stray` 继承 `AbstractSkeleton`，因此包含在此规则内；而且这条规则**没有检查狼是否被驯服**。同一方法中，针对普通猎物的规则才使用带未驯服限制的 `NonTameRandomTargetGoal`。见[狼的目标注册代码](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/09-Wolf.registerGoals.java:16)及[流浪者的继承关系](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/10-Stray.java:21)。

“会主动选择”也不保证每次相遇立即咬到：选敌有随机检查，实际攻击还受距离、寻路及更高优先级行为影响。坐下指令控制的是行动，其目标占用 `MOVE`、`JUMP`，优先于狼的近战动作；因此不能把“已驯服”与“坐下”混为一个条件。见[选敌实现](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/11-NearestAttackableTargetGoal.java:45)及[坐下行为实现](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/sources/12-SitWhenOrderedToGoal.java:10)。

这些结论限定于已校验的 **Java 26.3 原版**；未据此断言基岩版、旧版或后续版本具有相同行为。精确现场耗时和特殊坐下情形未进行运行时验证。