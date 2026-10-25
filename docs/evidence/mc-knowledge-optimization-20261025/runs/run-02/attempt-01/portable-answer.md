**结论：Java 版 26.3 中，骷髅在细雪中经过约 7 秒前置阶段、再约 15 秒转化阶段，变成流浪者。野生狼和已驯服的狼都会主动选择流浪者为攻击目标；驯服狼是否实际追击，还受坐下指令等条件影响。**

本次核对的是 **Java Edition 26.3 正式版原版服务端 JAR**，没有启动客户端或服务器。版本可对照[官方发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)；本地 JAR 的版本信息、SHA-256、反编译工具及证据索引保存在[来源记录](sources/.local/vanilla-26.3-evidence/provenance.json)。

**1．骷髅转化为流浪者**

| 阶段 | 行为与计时 |
|---|---|
| 接触细雪 | 游戏将骷髅判定为处于细雪中，开始累计专用转化计时。 |
| 前置阶段，约 7 秒 | 参数为 **140 game tick**；达到门槛后，进入转化状态，开始抖动。 |
| 转化阶段，再约 15 秒 | 倒计时设为 **300 game tick**；期间继续抖动，并且仍须处于细雪中。 |
| 转化完成 | 骷髅被新的流浪者实体替换；保留手持物和装备，非静音实体会触发转化音效。 |

这些参数和抖动条件直接见 [Skeleton](sources/.local/vanilla-26.3-evidence/Skeleton.java:20)，实际阶段推进见 [ConversionTracker.tick()](sources/.local/vanilla-26.3-evidence/ConversionTracker.java:53)。

还有几个关键细节：

- **任一阶段离开细雪，都会取消进度。**代码把前置计数设为 `-1`，并清除转化状态；再次进入需要重新走完整过程。因此，即使已经抖动，也不能移出细雪等待完成。
- **这不是靠冻伤触发的。**`Skeleton.canFreeze()` 返回 `false`；细雪接触标记与普通冻结伤害计时是分开的。见 [Skeleton.canFreeze()](sources/.local/vanilla-26.3-evidence/Skeleton.java:63) 和 [细雪效果处理](sources/.local/vanilla-26.3-evidence/InsideBlockEffectType.java:7)。
- **普通情况下，新流浪者恢复满血。**新实体初始化为最大生命值，转化流程保留装备，但不复制原骷髅剩余生命值。见[生命值初始化](sources/.local/vanilla-26.3-evidence/LivingEntity.java:296)和[转化属性复制](sources/.local/vanilla-26.3-evidence/ConversionType.java:16)。

**精确计时边界：**“7＋15＝约 22 秒”适合描述游戏体验，但不能严格写成固定 **440 tick**。26.3 代码先递增前置计数，达到 `140` 后启动转化；随后从 `300` 每次减一，**小于 0** 才完成，所以第二阶段实际经过 **301 次有效计时检查**。前置计数从初始 `0` 开始需 140 次，从离开细雪后的 `-1` 开始需 141 次，即合计 **441 或 442 次有效检查**。接触判定时序还会影响从画面上开始测量的边界；秒数按正常 **20 TPS** 换算，卡顿时现实耗时更长。该边界已经用[原版字节码](sources/.local/vanilla-26.3-evidence/ConversionTracker.javap.txt)交叉核对。

**2．野生狼与驯服狼是否主动攻击流浪者**

| 狼的状态 | 原版行为 |
|---|---|
| 自然生成、未驯服 | **会主动选中并攻击流浪者**，不需要玩家先攻击，也不需要先被流浪者攻击。 |
| 已驯服、正常站立活动 | **同样会主动攻击**，不需要主人先动手。 |
| 已驯服、正在执行坐下行为 | 坐下行为优先于近战行为，通常不会主动起身追击；“选到目标”和“实际攻击”是不同步骤。 |

核心证据是 `Wolf.registerGoals()`：

```java
this.targetSelector.addGoal(
    7, new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
);
```

这条规则**没有未驯服条件**，且流浪者的类声明为 `Stray extends AbstractSkeleton`，所以两种狼都适用。作为对照，狼捕猎部分普通动物所用的是 `NonTameRandomTargetGoal`。见 [Wolf 的完整目标注册](sources/.local/vanilla-26.3-evidence/Wolf-registerGoals.java:38)和 [Stray 的继承关系](sources/.local/vanilla-26.3-evidence/Stray.java:21)。

坐下行为会停止导航并占用移动、跳跃控制，见 [SitWhenOrderedToGoal](sources/.local/vanilla-26.3-evidence/SitWhenOrderedToGoal.java:8)。不过，**狼自身受到未被无敌判定拦截的伤害时，会解除坐下指令**，所以不能认为坐着的狼绝不会参战。见 [Wolf.hurtServer()](sources/.local/vanilla-26.3-evidence/Wolf-hurtServer.java:17)。

“会主动攻击”也不表示每次立即攻击：目标搜索、距离、视线、路径以及更高优先级行为都会影响实际表现。以上是 **26.3 原版代码的静态核验结论**，没有进行游戏内实测，也不外推至基岩版或其他版本。