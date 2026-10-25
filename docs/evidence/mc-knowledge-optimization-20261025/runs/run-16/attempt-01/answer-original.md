**结论：此次核查的 Java Edition 26.3 原版实现中，骷髅转为流浪者约需连续处于细雪中 22 秒；野狼和已驯服、正常站立的狼都会主动攻击流浪者。**

版本已对照[官方 26.3 发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。机制依据是本地 26.3 原版服务端 JAR，已通过服务端 bundle 内的 SHA-256 校验；关键计时另用字节码复核。以下属于静态代码调查，未启动游戏实测。

**1．骷髅在细雪中转化的完整过程**

按默认 **20 tick／秒**换算：

| 阶段 | 行为与时间 |
|---|---|
| 进入细雪 | 以实体的 `isInPowderSnow` 标志为条件，开始累计前置时间。 |
| 前置阶段 | 门槛为 **140 tick，约 7 秒**；此时尚未进入转化颤抖状态。 |
| 转化阶段 | 设置转化标志，骷髅开始颤抖；倒计时初值为 **300 tick**。 |
| 完成 | 倒计时每次更新减一，**减到负数时**替换为流浪者；因此这一阶段实际需要 **301 次有效更新，即 15.05 秒**。转化保留装备。 |

出处：`Skeleton` 初始化明确传入 `140`、`300`，`isShaking()` 返回转化状态；`ConversionTracker.tick()` 决定推进与完成条件。参见本次保存的[参数及构造器字节码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-16/attempt-01/workspace/.local/minecraft-26.3-vanilla-evidence.md:37)、[完整转化状态机](/private/tmp/hardrush-mc-optimization-20261025/runs/run-16/attempt-01/workspace/.local/minecraft-26.3-vanilla-evidence.md:161)。

有几个会影响结果的条件：

- **任一阶段离开细雪都会取消转化。**代码将前置计数设为 `-1`，清除转化标志；重新进入需要重新经历两个阶段。
- **严格逐 tick 计数略有边界差异：**通常已在细雪外更新过的骷髅从 `-1` 开始，前置需 141 次更新，再加 301 次，共 **442 次，约 22.10 秒**；新建追踪器初值为 `0` 时则共 441 次，约 22.05 秒。这是计时器更新次数，碰撞标志的更新时间还会影响从“接触瞬间”开始测量的结果。
- 骷髅的 `canFreeze()` 返回 `false`，但这**不会阻止转化**：转化检测使用细雪标志，不要求先累计普通冻结值。皮革装备也没有直接免除此转化的判断；皮革靴可以通过让实体站在细雪表面、未实际进入，间接避免触发。[细雪标志与冻结判断、皮革靴实现](/private/tmp/hardrush-mc-optimization-20261025/runs/run-16/attempt-01/workspace/.local/minecraft-26.3-vanilla-evidence.md:574)
- 仅在服务端、实体存活且未禁用 AI 时推进；卡顿或暂停更新会延长现实时间。[计时字节码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-16/attempt-01/workspace/.local/minecraft-26.3-vanilla-evidence.md:289)

**2．野狼与驯服狼是否主动攻击流浪者**

| 狼的状态 | 正常情况下的行为 |
|---|---|
| 自然生成、未驯服 | **会主动寻找并攻击流浪者**，无需先受到攻击。 |
| 已驯服、站立且可正常行动 | **同样会主动攻击**，无需主人先攻击流浪者。 |
| 已驯服、正在执行坐下命令 | 通常不会主动起身追击；坐下行为会阻止移动攻击。 |

直接证据是 `Wolf.registerGoals()` 对所有狼注册：

```java
new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
```

而 `Stray extends AbstractSkeleton`，因此流浪者包含在目标中。此规则**没有未驯服限制**；狼对部分动物的捕猎规则才使用检查 `!isTame()` 的 `NonTameRandomTargetGoal`。[狼的目标注册、流浪者继承关系及驯服条件对照](/private/tmp/hardrush-mc-optimization-20261025/runs/run-16/attempt-01/workspace/.local/minecraft-26.3-vanilla-evidence.md:360)

“会主动攻击”不保证发现后立即打中：其他更高优先级目标、搜索距离、寻路和攻击距离都会影响实际行动。坐下也不是永久免战状态：狼受伤时会清除坐下命令。[坐下行为与受伤处理](/private/tmp/hardrush-mc-optimization-20261025/runs/run-16/attempt-01/workspace/.local/minecraft-26.3-vanilla-evidence.md:499)

**适用范围与不确定性：**上述结论限定于此次核查的 **Java 26.3 正式版服务端制品**，不外推到快照、基岩版或模组环境。数值和逻辑已由代码支持；实际动画呈现、碰撞起始时刻及复杂寻路场景未经运行时验证。[制品身份与完整证据入口](/private/tmp/hardrush-mc-optimization-20261025/runs/run-16/attempt-01/workspace/.local/minecraft-26.3-vanilla-evidence.md:7)