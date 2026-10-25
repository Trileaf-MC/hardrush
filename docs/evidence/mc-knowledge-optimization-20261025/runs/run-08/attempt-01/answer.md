**Java 26.3 中，骷髅在细雪中约 22 秒后转化为流浪者；野生狼和能够正常行动的驯服狼，都会主动攻击流浪者。** 驯服狼若被命令坐下，则需要区分“选择攻击目标”和“起身追击”。

本次核对的是 **Java 26.3 正式版原版服务端 JAR**，版本信息与服务端包内 SHA-256 校验通过。版本可参见[官方发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)，本地文件身份见[校验与出处记录](.local/mc-knowledge/verification-26.3/provenance.json)。以下依据反编译源码，并用字节码复核关键分支；没有启动游戏进行实测。

**1．骷髅转化为流浪者的完整过程**

普通骷髅的转化由 `Skeleton` 配置、`ConversionTracker` 执行：条件是 `isInPowderSnow`，前置门槛为 **140 tick**，随后启动 **300 tick** 的转化倒计时。出处：[Skeleton 的配置](.local/mc-knowledge/verification-26.3/Skeleton.java:20)、[计时逻辑](.local/mc-knowledge/verification-26.3/ConversionTracker.java:53)。

| 阶段 | 原版行为 | 按 20 TPS 换算 |
|---|---|---|
| 进入细雪、累计前置进度 | 处于细雪中时，前置计数逐 tick 增加；达到 140 后开始转化 | 约 7 秒 |
| 转化中 | 仍是骷髅，开始抖动；倒计时初始化为 300，逐 tick 减少，减到 **小于 0** 时完成 | 约 15 秒 |
| 转化完成 | 替换为流浪者，并保留装备；非静音实体触发转化声音事件 | 瞬间完成 |

抖动状态由 `Skeleton.isShaking()` 直接读取转化标志；装备保留见转化参数与装备复制逻辑。出处：[抖动判定](.local/mc-knowledge/verification-26.3/Skeleton.java:35)、[完成转化](.local/mc-knowledge/verification-26.3/ConversionTracker.java:97)、[装备转移](.local/mc-knowledge/verification-26.3/ConversionType.java:38)。

还需要注意以下细节：

- **任一阶段离开细雪，都会取消当前转化并重置前置进度。** 重新进入后需要重新累计；已经开始抖动也必须继续留在细雪中。[退出分支](.local/mc-knowledge/verification-26.3/ConversionTracker.java:68)
- **严格计时并非恰好 440 tick。** 倒计时从 300 减到 −1，需要 301 次更新。通常骷髅先在细雪外更新过，前置计数已被设为 −1，此时还需要 141 次前置更新，合计 **442 次有效更新，约 22.10 秒**；若前置计数从初始值 0 开始，则为 441 次，约 22.05 秒。这里从跟踪器读到细雪状态开始计算，实际入雪时刻还有 tick 对齐差异。[字节码复核](.local/mc-knowledge/verification-26.3/ConversionTracker.tick.javap.txt)
- 这是独立的转化计时：骷髅的 `canFreeze()` 返回 `false`，转化条件也不检查普通冻伤进度。计时只在**服务端、实体存活且 AI 未禁用**时执行；现实耗时还取决于实体是否持续更新及服务器 TPS。[骷髅冻伤判定](.local/mc-knowledge/verification-26.3/Skeleton.java:63)、[执行条件](.local/mc-knowledge/verification-26.3/ConversionTracker.java:54)

**2．野生狼与驯服狼是否主动攻击流浪者**

| 狼的状态 | 行为 |
|---|---|
| 自然生成、未驯服 | **会主动攻击流浪者**，无需先被流浪者攻击 |
| 已驯服，正常站立且能够行动 | **同样会主动攻击**，无需主人先攻击或受伤 |
| 已驯服，被命令坐下且坐下行为正常生效 | **不会仅因附近出现流浪者就起身追击**；攻击目标选择仍可能发生 |

关键证据是 `Wolf.registerGoals()` 注册了：

```java
new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
```

而 `Stray` 继承 `AbstractSkeleton`，因此属于这条规则的目标。这条规则对野生狼和驯服狼都有效，没有 `!isTame()` 条件。出处：[狼的目标规则](.local/mc-knowledge/verification-26.3/Wolf.registerGoals.java:57)、[流浪者的继承关系](.local/mc-knowledge/verification-26.3/Stray.java:21)。

坐下行为的优先级为 2，近战攻击为 5；坐下行为占用移动控制，因此正常生效时会阻止追击。出处：[行为优先级](.local/mc-knowledge/verification-26.3/Wolf.registerGoals.java:41)、[坐下行为实现](.local/mc-knowledge/verification-26.3/SitWhenOrderedToGoal.java:11)。

“会主动攻击”表示存在主动选敌与攻击机制，实际发动仍受目标有效性、发现范围、视线、路径和更高优先级行为影响。[目标查找逻辑](.local/mc-knowledge/verification-26.3/NearestAttackableTargetGoal.java:45)、[目标过滤条件](.local/mc-knowledge/verification-26.3/TargetingConditions.java:59)。上述结论限定于本次核对的 **Java 26.3 原版实现**，未外推至基岩版或其他 Java 版本。