**Java 版 26.3 中，骷髅在细雪里约 22 秒后转化为流浪者；野生狼和已驯服、能够正常行动的狼都会主动攻击流浪者。**

以下依据本地 **26.3 原版服务端 JAR** 的反编译代码，并对转化计时核对了字节码。JAR 已通过服务端包内 SHA-256 校验；未启动游戏客户端或服务器。版本可对照[官方 26.3 发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)，制品哈希与查询来源保存在[证据索引](sources/.local/mc-knowledge/verified-26.3/provenance.json)。

**1．骷髅在细雪中的完整转化过程**

| 阶段 | 行为与时间 |
|---|---|
| 接触细雪 | 条件是实体的 `isInPowderSnow` 状态成立；没有要求头部浸没，也没有寒冷生物群系限制。 |
| 前置阶段 | `FreezingTime` 逐 tick 增加，达到 **140** 后开始转化，通常概括为约 **7 秒**。 |
| 转化阶段 | 骷髅开始抖动，`StrayConversionTime` 初始化为 **300**，随后逐 tick 递减，约 **15 秒**后变成流浪者。 |
| 完成 | 创建流浪者实体并移除原骷髅；手持物品及穿戴装备会保留。普通情况下，新流浪者以满血出现，原骷髅的残血不会照搬。 |

140、300、目标类型以及抖动条件见 [Skeleton](sources/.local/mc-knowledge/verified-26.3/Skeleton.java:20)；装备保留见 [ConversionType](sources/.local/mc-knowledge/verified-26.3/ConversionType.java:40)。满血结论来自新实体构造时调用 `setHealth(getMaxHealth())`，且转化流程没有复制旧生命值，见[构造器字节码](sources/.local/mc-knowledge/verified-26.3/LivingEntity.constructor-tail.bytecode.txt:54)。

几个会改变结果的重要条件：

- **两个阶段都必须持续处于细雪中。**26.3 中，一旦检测到离开细雪，就把前置计时设为 `-1`，并取消转化状态；已经抖动也会取消。重新进入需要重计，不能把多次接触时间累加。
- 骷髅自身的 `canFreeze()` 返回 `false`，因此这不是“先受到冻结伤害，再转化”。转化计时独立运行；即使穿着皮革装备，只要仍被判定接触细雪，转化条件就成立。
- 必须存活、启用 AI，并持续获得服务端实体 tick。`NoAI` 会使这段逻辑不推进；暂停、区块不再处理实体或 TPS 降低都会影响现实等待时间。

出处：[ConversionTracker.tick](sources/.local/mc-knowledge/verified-26.3/ConversionTracker.java:53)、[细雪状态与冻结值分别处理](sources/.local/mc-knowledge/verified-26.3/InsideBlockEffectType.java:7)。

**“约 22 秒”不是严格的 440 tick。**倒计时从 300 开始，先减一，再判断是否 **小于 0**，因此转化阶段实际需要 301 次有效检查。普通骷髅先在细雪外经历过检查时，前置计时从 `-1` 开始，需要 141 次有效检查，总计通常为 **442 次检查，按 20 TPS 折算约 22.1 秒**。初始计数及方块接触更新顺序还会影响进入细雪的计时边界。这是代码推导，并非秒表实测；关键判定已由[字节码复核](sources/.local/mc-knowledge/verified-26.3/ConversionTracker.tick.bytecode.txt)。

**2．野生狼、驯服狼是否主动攻击流浪者**

| 狼的状态 | 结论 |
|---|---|
| 自然生成、未驯服 | **会主动选择流浪者作为攻击目标**，无需流浪者先攻击。 |
| 已驯服、能够正常行动 | **同样会主动攻击**，无需主人先攻击流浪者。 |
| 被命令坐下，且坐下行为正在生效 | 通常不会起身追击；坐下行为占用移动和跳跃控制，但骷髅类目标规则仍然保留。 |

关键证据是狼在 `registerGoals()` 中注册了优先级 **7** 的：

```java
new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
```

这条规则没有“仅未驯服”的限制，而流浪者明确继承 `AbstractSkeleton`。因此，驯服不会消除这项主动攻击行为。出处：[狼的目标注册](sources/.local/mc-knowledge/verified-26.3/Wolf.registerGoals.java)、[流浪者的继承关系](sources/.local/mc-knowledge/verified-26.3/Stray.java:21)、[坐下行为占用的控制标志](sources/.local/mc-knowledge/verified-26.3/SitWhenOrderedToGoal.constructor.bytecode.txt)。

“会主动攻击”表示 AI 具备主动选敌和攻击行为，并不保证每次相遇都立即咬到：其他更高优先级目标、坐下、逃生和路径障碍都可能影响实际表现。以上结论限定于 **26.3 原版代码的静态核查**，未作运行时验证，也不外推到基岩版或其他 Java 版本。