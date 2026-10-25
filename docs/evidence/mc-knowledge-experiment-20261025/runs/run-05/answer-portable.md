**Java 版 26.3 原版中，骷髅转化为流浪者约需“7 秒预备＋15 秒转化”；发抖后仍须留在细雪中。野狼和正常站立、可行动的驯服狼都会主动攻击流浪者。**

版本已通过 [Mojang 的 26.3 正式版公告](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)及[对应版本元数据](https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json)核实。以下关键结论来自该版本官方原版 JAR 的静态分析，未启动游戏客户端或服务器。

**1．骷髅在细雪中的完整转化过程**

| 阶段 | 行为及时间 |
|---|---|
| 进入细雪，开始预备计时 | 游戏判定骷髅处于细雪中，累加专用计数；阈值为 **140 游戏刻，约 7 秒**。 |
| 开始转化 | 达到阈值后，骷髅开始发抖，转化倒计时设为 **300 游戏刻，约 15 秒**。此时实体仍是骷髅。 |
| 完成转化 | 倒计时结束后，原骷髅被流浪者替换；总体约 **22 秒**。 |

直接依据是 `Skeleton` 构造函数中的 **140、300** 两个参数，以及 `isShaking()`、`ConversionTracker.tick()` 的实现。可查看本次生成的 [Skeleton 反汇编](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/01-Skeleton.bytecode.txt:7)和[转化计时控制流](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/02-ConversionTracker.bytecode.txt:57)。

几个重要细节：

- **两个阶段都要求继续处于细雪中。**26.3 中，一旦游戏判定已经离开细雪，就会取消转化、停止发抖，并重置前段进度；重新进入后需要重新开始。此结论直接来自上面 `ConversionTracker.tick()` 的离雪分支。
- **转化不依赖冻伤扣血。**普通骷髅的 `canFreeze()` 返回 `false`，转化使用独立计时。实际进入细雪后，皮革装备也不会免除转化；皮革靴让实体站在细雪表面的情况需要另行区分。[代码依据](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/01-Skeleton.bytecode.txt:83)
- **装备保留，原来的残血不继承。**普通原版条件下，转化出的流浪者从满血开始，保留手持物品和穿戴装备；流浪者自身免疫普通细雪冻结伤害。相关创建、装备复制和免疫标签的依据收录在[证据索引](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/03-evidence.md)。

“约 22 秒”还有逐刻边界：该版后段从 300 递减到 **小于 0** 才完成，需要 **301 次有效处理**；通常骷髅此前在雪外活动过，前段从 −1 累加到 140，需要 **141 次**，合计通常为 **442 次有效处理，20 TPS 下约 22.10 秒**。若从构造时尚未重置的初值 0 开始，则合计为 441 次。这是代码推导，实际从接触细雪起观察的时间还受碰撞状态更新、实体是否持续运算和 TPS 影响。[计时依据](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/02-ConversionTracker.bytecode.txt:57)

**2．狼会不会主动攻击流浪者？**

| 狼的状态 | 是否主动攻击 |
|---|---|
| 自然生成、未驯服，正常自由行动 | **会**，不需要先被流浪者攻击。 |
| 已驯服，正常站立且可以行动 | **也会**，不需要主人先攻击或先受伤。 |
| 已驯服，正在正常执行坐下命令 | **通常不会主动追击**，坐下行为会阻止近战追击。 |

依据是 `Wolf.registerGoals()` 为狼统一注册了针对 **`AbstractSkeleton`** 的主动选敌规则，而 `Stray` 正是其子类。**这条规则没有“必须未驯服”的条件**，天然生成与转化产生的流浪者均适用。[狼的目标规则](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/04-Wolf.bytecode.txt:44)、[流浪者的继承关系](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/05-Stray.bytecode.txt:1)

实际能否立即咬到，还取决于目标发现、寻路、攻击距离及其他更高优先级行为。坐下也不是永远不反击的保证：狼受到伤害时，伤害处理会先清除坐下命令。[坐下行为](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/06-SitWhenOrderedToGoal.bytecode.txt:25)、[受伤处理](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/04-Wolf.bytecode.txt:1006)

以上机制限定于 **Java 26.3 正式版原版**，不外推到基岩版或其他版本。本次未做游戏内实测；[完整证据索引](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-05/sources/03-evidence.md)包含官方 JAR 下载地址、校验值和无需启动游戏的复核方法。