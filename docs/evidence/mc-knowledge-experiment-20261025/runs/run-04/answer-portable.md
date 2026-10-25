**Java 版 26.3 中，骷髅转化为流浪者的通常描述是“约 7 秒预备＋约 15 秒发抖”；野狼和站立的驯服狼都会主动攻击流浪者。一个关键细节是：转化完成前离开细雪，会取消整个过程。**

本次使用 Vineflower 反编译本机 26.3 缓存，并下载官方客户端核对：文件 SHA-1 与[官方 26.3 元数据](https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json)一致，以下关键机制类与官方客户端逐字节相同。没有启动游戏客户端或服务器。

**1. 骷髅在细雪中的完整转化过程**

| 阶段 | 行为与时间 |
|---|---|
| 进入细雪 | 游戏将骷髅标记为处于细雪中，开始前置计时。条件不是“头部完全浸没”。 |
| 前置阶段 | 计时达到 **140 tick 的阈值**，即通常所说的约 **7 秒**，开始发抖。 |
| 转化阶段 | 转化倒计时设为 **300 tick**，继续发抖；约 **15 秒**后转化为流浪者。 |
| 完成 | 创建流浪者并移除原骷髅，保留手持物、穿戴装备及其掉落概率；通常以满生命值出现，并播放转化声音，静音实体除外。 |

这些参数及发抖状态直接见 [Skeleton.java](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/sources/01-Skeleton.java:20)；实际计时和转换调用见 [ConversionTracker.java](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/sources/02-ConversionTracker.java:53)。[Wiki 的转化段落](https://minecraft.wiki/w/Stray#Converting)也采用“7 秒＋15 秒”的概述。

有几个会影响实际结果的条件：

- **两个阶段都必须继续处于细雪中。** 即使已经发抖，只要计时器检测到离开细雪，就会取消转化、重置前置进度；重新进入需要从头开始。证据是计时器第 55–69 行。
- **骷髅不会因为这段过程受到普通冻伤。** 它的 `canFreeze()` 直接返回 `false`；转化使用独立计时器，因此穿皮甲也不会直接阻止转化。不过皮靴可能让它站在细雪表面，未实际进入。见 [冻结免疫代码](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/sources/01-Skeleton.java:63)和[皮靴碰撞规则](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/sources/03-PowderSnowBlock.java:139)。
- 计时只在服务端、实体存活且 AI 开启时推进。实体停止更新或游戏暂停期间，不按现实时间继续计算。

**严格到 tick 时，“约 22 秒”需要补充边界说明：**转化倒计时先递减，直到 **小于 0** 才完成，因此从 300 开始实际需要 **301 次更新**。普通骷髅在雪外更新后，前置计数被设为 −1，再进入需 **141 次更新**达到 140；合计 **442 次有效更新**，按 20 TPS 折算约 **22.10 秒**。若前置计数仍是构造初值 0，则为 441 次。这里是代码更新次数；从画面上“刚碰到细雪”开始计时，还涉及 tick 对齐和显示延迟，不能宣称经过了实机逐帧测量。

**2. 野狼和驯服狼是否主动攻击流浪者**

| 狼的状态 | 对流浪者的行为 |
|---|---|
| 自然生成、未驯服 | **会主动选为攻击目标并追击**，无需流浪者先攻击，也无需玩家介入。 |
| 已驯服、站立且可正常行动 | **同样会主动攻击**，无需主人先动手或先受伤。 |
| 已驯服、被命令坐下 | 通常保持坐下，不主动追击；受到伤害等情况可能解除坐下状态，随后参战。 |

决定性证据是 [Wolf.registerGoals()](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/sources/04-Wolf.java:130)：狼针对 `AbstractSkeleton` 注册了普通 `NearestAttackableTargetGoal`，**没有要求“未驯服”**；而 [Stray.java](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/sources/05-Stray.java:21)明确显示流浪者继承 `AbstractSkeleton`。狼对其他猎物使用的 `NonTameRandomTargetGoal` 才有未驯服限制。

坐下行为的优先级高于扑跃和近战攻击；狼成功驯服时还会自动设置坐下命令，因此刚驯服后通常需要让它站起来。见[坐下行为代码](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/sources/06-SitWhenOrderedToGoal.java:8)和[驯服处理](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/sources/04-Wolf.java:508)。

“主动攻击”表示具备主动选敌、追击的 AI 规则；能否实际咬到仍受寻路、距离、攻击视线及更高优先级行为影响，并非看见后必定立即命中。

以上结论限定于 **原版 Java 26.3**，依据静态代码分析，不外推到基岩版或其他版本。完整来源、校验结果和复核命令保存在[本次调查证据说明](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/sources/07-README.md)。