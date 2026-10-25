**Java 26.3 原版中，骷髅需要持续处于细雪中，经过约 7 秒准备和 15 秒转化，成为流浪者；野生狼和已驯服、能自由行动的狼，都会主动把流浪者作为攻击目标。**

版本已核对为 **Java Edition 26.3 正式版**，不是基岩版同名版本。机制结论来自本地原版服务端 JAR 的反编译代码，并对关键常数和分支核查了字节码。官方版本入口见 [26.3 发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。

**1．骷髅在细雪中转化的过程**

| 阶段 | 行为 | 时间 |
|---|---|---|
| 接触细雪 | 引擎将骷髅标记为 `isInPowderSnow`，开始积累准备计时 | 从有效检测开始 |
| 准备阶段 | 持续累积 `FreezingTime`；此时尚未进入转化颤抖状态 | 阈值 **140 tick，约 7 秒** |
| 转化阶段 | 开始颤抖，`StrayConversionTime` 从 300 倒计时；仍须持续处于细雪中 | 参数 **300 tick，约 15 秒** |
| 完成 | 原骷髅被流浪者替换；保留装备、自定义名称、状态效果等；非静音时触发转化音效事件 | 倒计时减至负数时 |

出处：[`Skeleton` 的参数及颤抖判定](sources/.local/investigation-26.3/Skeleton.java:20)、[`ConversionTracker.tick()` 的完整流程](sources/.local/investigation-26.3/ConversionTracker.java:53)、[转化时的数据与装备复制](sources/.local/investigation-26.3/ConversionType.java:16)。

有几个影响实际使用的细节：

- **两个阶段中途离开细雪，都会取消转化并重置准备进度。**已经开始颤抖，也不能离开细雪等待完成；重新进入后重新准备。
- 计时只在服务端、实体存活且未设置 `NoAI` 时执行；实体停止获得 tick 时不会按现实时间继续倒计时。生物群系、天光和时段不在这条转化判定中。
- 转化判断直接使用细雪接触状态。`Skeleton.canFreeze()` 虽然返回 `false`，仍然可以发生这项转化；细雪设置接触状态与普通冻结计时是分开的。[接触状态代码](sources/.local/investigation-26.3/InsideBlockEffectType.java:7)

**精确到 tick 时，通常约为 22.10 秒。**此前在雪外时，准备计数会被设为 `-1`，所以重新进入后需 **141 次有效检测**达到 140；随后从 300 减至 `-1`，需 **301 次更新**，合计 **442 次有效检测**。若刚创建的实体计数仍为初始值 0，则少一次，为 441 次。上述秒数按 **20 TPS** 换算；接触方块到首次检测的 tick 顺序也会影响外部测量起点。[对应字节码](sources/.local/investigation-26.3/ConversionTracker.tick.bytecode.txt:1)

**2．狼是否主动攻击流浪者**

| 狼的状态 | 结论 |
|---|---|
| 自然生成、未驯服 | **会主动选择流浪者为攻击目标**，无需先受攻击 |
| 已驯服、能够自由行动 | **同样会主动选择流浪者为攻击目标**，无需主人先攻击 |
| 已驯服，坐下行为正在生效 | 坐下行为阻止正常追击，不能据此预期它会主动冲过去攻击 |

直接证据是 `Wolf.registerGoals()`：

```java
this.targetSelector.addGoal(
    7, new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
);
```

流浪者的类声明为 `Stray extends AbstractSkeleton`，因此属于这条规则的目标。该规则使用普通 `NearestAttackableTargetGoal`，**没有未驯服限定**；代码中其他部分使用的 `NonTameRandomTargetGoal` 才检查 `!isTame()`。[狼的目标注册](sources/.local/investigation-26.3/Wolf.registerGoals.java:18)、[流浪者的继承关系](sources/.local/investigation-26.3/Stray.java:21)、[未驯服限定规则](sources/.local/investigation-26.3/NonTameRandomTargetGoal.java:16)。

“会主动选为目标”不保证每次立即成功攻击：目标搜索、其他优先级更高的行为和寻路仍会影响行动。坐下行为优先级为 2，高于近战攻击的 5；狼自身受伤时，代码还会取消坐下命令。[坐下行为](sources/.local/investigation-26.3/SitWhenOrderedToGoal.java:11)、[受伤时取消坐下](sources/.local/investigation-26.3/Wolf.hurtServer.java:3)。

本次为**静态代码调查，未启动客户端或服务器**；秒数不是游戏内实测结果。本地完整性检查确认提取 JAR 与本地服务端 bundle 中的 SHA-256 一致，未重新下载整个官方 JAR 比对。完整来源、哈希及复查命令已保存在[本次证据说明](sources/.local/investigation-26.3/README.md)。