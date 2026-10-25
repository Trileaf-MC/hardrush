**结论：Java 原版 26.3 中，骷髅转化为流浪者的标称流程是“约 7 秒前置阶段＋约 15 秒抖动转化”；未驯服狼和已驯服狼都具备主动攻击流浪者的规则。** 但转化中断条件、tick 边界和狼的坐下状态需要分别说明。

本次核查的是 **26.3 正式版服务端 JAR**，其 `version.json` 标记为稳定版，提取 JAR 的 SHA-256 与服务端包内清单匹配。官方也有对应的 [26.3 发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。机制结论来自该版本代码及关键方法字节码。

**1．骷髅在细雪中的完整转化过程**

| 阶段 | 行为 | 标称时间，按 20 TPS |
|---|---|---|
| 前置阶段 | 游戏持续判定骷髅处于细雪中，累计 `FreezingTime`；尚未进入转化抖动状态 | 140 tick，约 7 秒 |
| 转化阶段 | 开始抖动，`StrayConversionTime` 从 300 开始倒数；此时仍是骷髅 | 300 tick，约 15 秒 |
| 完成 | 创建流浪者并移除原骷髅，迁移装备等数据；非静音实体播放转化音效 | 合计约 22 秒 |

直接依据是 [`Skeleton` 的初始化参数及 `isShaking()`](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/Skeleton.java:20)，以及 [`ConversionTracker` 的完整流程](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/ConversionTracker.java:53)。装备迁移见 [`ConversionType.SINGLE`](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/ConversionType.java:40)。

需要特别注意：

- **两个阶段都必须持续满足“处于细雪中”。** 这份 26.3 实现一旦检测到离开细雪，就把前置计数重置为 `-1`，并取消转化状态；**已经抖动也会取消**，重新进入后重新计时。[中断分支源码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/ConversionTracker.java:67)
- **严格计时并非恰好 440 tick。** 转化倒计时要从 300 减到 **负数**才完成，因此需要 301 次后续有效更新；前置计数从初值 0 开始需要 140 次，从雪外重置后的 `-1` 开始需要 141 次。也就是分别为 **441／442 次有效更新，约 22.05／22.10 秒**。实际从接触细雪那一刻测量，还涉及方块效果与实体更新的先后顺序。[源码及对应字节码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/ConversionTracker-tick-bytecode.txt)
- **转化计时与通常的冻结计数是两套机制。** 骷髅的 `canFreeze()` 返回 `false`，但细雪仍会设置转化所依赖的 `isInPowderSnow` 状态。[骷髅实现](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/Skeleton.java:63)、[细雪效果实现](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/InsideBlockEffectType.java:7)

上述秒数要求实体持续获得更新、仍然存活且 AI 未被禁用；区块停止更新或 TPS 降低都会延长现实等待时间。

**2．狼是否主动攻击流浪者？**

| 狼的状态 | 原版 26.3 的行为 |
|---|---|
| 自然生成、未驯服 | **会主动选择流浪者为攻击目标**，不要求流浪者先攻击 |
| 已被玩家驯服、可自由行动 | **同样会主动选择流浪者**，不要求主人先攻击 |
| 已驯服、正常执行坐下命令 | 坐下行为通常会阻止追击和近战动作，不能据此判断它失去了主动选敌规则 |

关键代码是 `Wolf.registerGoals()` 中优先级 7 的规则：

```java
new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, false)
```

[`Stray extends AbstractSkeleton`](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/Stray.java:21)，所以流浪者符合目标类型。该规则使用普通选敌 Goal，**没有“必须未驯服”的限制**；相比之下，狼捕猎动物、小海龟的规则使用了 `NonTameRandomTargetGoal`。[狼的规则注册](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/Wolf-registerGoals.java:49)、[未驯服限定的实际实现](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/NonTameRandomTargetGoal.java:17)

**“会主动攻击”不等于“一出现就立即咬到”。** 目标获取仍受搜索检查、距离、视线、敌我关系及更高优先级目标影响，实际近战还需要能够接近目标。坐下行为优先级高于近战，并占用移动／跳跃控制；狼自身受伤时又会解除坐下命令。[目标条件](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/TargetingConditions-test.java:9)、[坐下行为](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/SitWhenOrderedToGoal.java:8)、[受伤处理](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/Wolf-hurtServer.java:17)

本次只做了静态源码与字节码核查，**没有启动客户端或服务器进行运行时验证**，也不推定其他版本、基岩版或模组环境相同。完整哈希、原始查询输出和复查命令已保存到[本次证据索引](/private/tmp/hardrush-mc-optimization-20261025/runs/run-09/attempt-01/workspace/.local/vanilla-26.3-evidence/README.md)。