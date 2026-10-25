**Java 版 26.3 中，骷髅转化为流浪者通常需要约 22 秒；未驯服狼与正常站立的驯服狼，都能主动攻击流浪者。** 其中，转化计时存在 tick 边界，坐下状态也会影响狼实际是否追击。

本次核对的是 **26.3 原版服务端 JAR**，工具确认其 SHA-256 与服务端包记录匹配；关键逻辑另用字节码复核。版本对应[官方 26.3 发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。以下结论来自静态检查，未启动游戏客户端或服务器。

**1．骷髅在细雪中的完整转化过程**

| 阶段 | 行为与时间 |
|---|---|
| 接触细雪 | 游戏将实体标记为 `isInPowderSnow`，骷髅开始积累独立的转化前置计时。 |
| 前置阶段 | 计数达到 **140 tick**，约 **7 秒**，进入转化阶段。 |
| 转化阶段 | 骷髅开始颤抖，转化倒计时设为 **300 tick**，约 **15 秒**。此时仍然是骷髅。 |
| 完成 | 倒计时结束后，创建流浪者实体并移除原骷髅。 |

这些参数直接来自 `Skeleton` 的构造逻辑；`isShaking()` 直接返回是否正在转化。[骷髅源码及参数](sources/.local/investigation-26.3/vanilla-evidence.txt:12)

有几个关键细节：

- **两个阶段都必须持续处于细雪中。** 在 26.3 的实现里，只要一次有效检查发现已经离开细雪，就将前置计数设为 `-1`，并取消正在进行的转化。即使已经颤抖，也会取消；重新进入后需要重新计时。
- **这不是冻伤累积。** 普通骷髅的 `canFreeze()` 返回 `false`，不会因这段过程承受通常的冻结伤害；转化检查直接使用细雪接触标记。皮革装备本身也没有被列为取消转化的条件，但装备可能影响实体能否进入细雪。
- 计时要求实体存活、服务端正在执行其 tick，而且没有关闭 AI；`NoAI` 骷髅不会推进这个过程。[转化状态机](sources/.local/investigation-26.3/vanilla-evidence.txt:130)、[细雪标记与冻结的区别](sources/.local/investigation-26.3/vanilla-evidence.txt:323)

**精确时间不能简单写成“440 tick”。** 代码先递减倒计时，再检查是否 **小于 0**，所以从 `300` 开始实际需要 **301 次有效检查**。前置计数若从初始值 `0` 开始，需要 140 次；若此前在细雪外被重置为 `-1`，则需要 141 次。因此分别是 **441 或 442 次有效检查**，按 20 TPS 约为 **22.05 或 22.10 秒**。这是状态机调用次数推导；从肉眼看到接触细雪开始计时，还受碰撞检测与 tick 相位影响，服务器掉 TPS 也会延长现实时间。[计时边界的字节码](sources/.local/investigation-26.3/vanilla-evidence.txt:255)

转化后的流浪者保留原骷髅的手持物、护甲及相应装备掉落概率；**原骷髅损失的生命值不会继承，通常会以满血出现**。这是创建新实体后转移装备和状态的结果。[实体创建及状态转移](sources/.local/investigation-26.3/vanilla-evidence.txt:354)

**2．野生狼与驯服狼是否主动攻击流浪者**

| 狼的状态 | 结论 |
|---|---|
| 自然生成、未驯服 | **会主动选择流浪者并尝试攻击**，不需要先受到流浪者攻击。 |
| 已驯服、正常站立 | **同样会主动选择流浪者并尝试攻击**，不需要主人先动手。 |
| 已驯服、按命令坐下且坐下行为正常运行 | 通常保持坐下，不会为了附近的流浪者主动起身追击。 |

决定性的代码是 `Wolf.registerGoals()`：

```java
this.targetSelector.addGoal(
    7, new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
);
```

`Stray` 继承 `AbstractSkeleton`，因此属于这个主动选敌规则的目标。该规则**没有检查狼是否已驯服**；狼捕猎部分动物时使用的 `NonTameRandomTargetGoal` 才明确限制为未驯服状态。[狼的选敌规则](sources/.local/investigation-26.3/vanilla-evidence.txt:655)、[流浪者继承关系](sources/.local/investigation-26.3/vanilla-evidence.txt:890)、[未驯服限制的对照实现](sources/.local/investigation-26.3/vanilla-evidence.txt:1053)

需要区分**选中目标**与**实际攻击**：距离、寻路、目标有效性，以及更高优先级的行为都会影响最终动作。坐下行为优先级高于近战攻击，并占用移动资源；不过，狼受到攻击时会解除坐下命令，因此“坐下”不能理解为任何情况下都不会参战。[坐下条件](sources/.local/investigation-26.3/vanilla-evidence.txt:1228)、[受伤解除坐下命令](sources/.local/investigation-26.3/vanilla-evidence.txt:1282)

以上结论限定于所核对的 **Java 26.3 原版实现**；毫秒级实际表现未做运行验证。全部关键源码、字节码、查询参数和 JAR 校验信息均保存在本次生成的[可复核证据文件](sources/.local/investigation-26.3/vanilla-evidence.txt:1)中。