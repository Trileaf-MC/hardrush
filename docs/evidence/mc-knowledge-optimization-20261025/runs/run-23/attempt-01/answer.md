**普通刷怪笼会在范围内没有合格玩家时停止刷怪，并暂停倒计时；试炼刷怪笼在激活后，玩家离开探测范围也可以继续本轮生成。** 两者都要求所在区块仍在执行方块实体 tick；区块卸载或停止运算后，都不会继续生成。依据：[普通笼入口](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:95)、[试炼 ACTIVE 状态](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerState.tickAndGetNext.java:38)、[区块运算条件](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/LevelChunk.isTicking.java:22)。

本次核对的是 **Java Edition 26.3 正式版原版服务端 JAR**，不是 Java 运行环境版本。[官方发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)标明发布日期为 2026-09-15。

| 项目 | 普通刷怪笼 | 试炼刷怪笼 |
|---|---|---|
| 默认玩家激活／探测距离 | **16 格**，从笼子中心计算三维距离，严格 `<16` | **14 格**；实现按玩家与笼子的方块坐标计算距离，严格 `<14` |
| 合格玩家 | 存活、非旁观者；创造模式也能激活，不要求视线 | 排除创造与旁观模式；首次登记玩家要求视线 |
| 玩家全部离开范围 | 保留剩余 `Delay`，停止递减；回来后继续 | 已登记玩家不会仅因离开而被移除；本轮仍可继续 |
| 默认生成延迟 | 初始 `Delay=20` 刻；重置时从 **200～799 刻**均匀抽取，800 不包含 | 代码默认 `ticks_between_spawn=40` 刻；自然生成配置有覆盖，见下文 |
| 一次尝试数量 | 到期后最多 **4 次候选生成尝试**，不保证成功 4 只 | 每个符合条件的 tick **调用一次生成，尝试一只** |
| 数量限制 | 默认附近同类上限 **6** | 不使用普通笼的“附近同类 6 只”检查；按本笼追踪的实体和本轮配额限制 |

上述默认值及检查分别见 [BaseSpawner](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:43)、[普通笼玩家筛选](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/EntityGetter.hasNearbyAlivePlayer.java:11)、[试炼默认配置](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerConfig.java:109)、[试炼玩家筛选](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/PlayerDetector.java:26)。秒数均按正常 **20 TPS** 换算。

**普通刷怪笼的计时与失败行为：**

- `Delay>0` 时，每个激活 tick 减一；减到零的那个 tick 尚不尝试生成，下一个激活 tick 才进入生成循环。`Delay=-1` 会先抽取新延迟。
- 最多 4 次尝试中，**只要至少成功一次**，本轮结束便重置随机延迟。
- 如果全部因为碰撞、生成规则、光照或生物空间检查等条件失败，**不重置延迟**，继续保持到期状态；下一个激活 tick 再尝试最多 4 次。
- 执行到数量检查时若已达到附近上限，或遇到无效实体类型、实体加载失败、加入世界失败，则**立即重置延迟并结束本轮**。所以“生成失败”并非统一处理。
- 默认数量检查区域是笼子所在的 **1×1×1 方块包围盒向六面各扩 4 格，即 9×9×9**；按相同 Java 实体运行时类查询，也会计入其他来源的匹配实体，不是全局自然刷怪上限。

这些分支均可在 [serverTick](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:95) 与 [delay](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:201) 复核。

**试炼刷怪笼需要区分代码默认值和试炼之地实际配置：**

设额外登记玩家数为 `A=max(登记人数−1,0)`，代码默认本轮总量为 **`6+2A`**，同时追踪上限为 **`2+A`**。通用计算公式使用向下取整。自然生成配置会覆盖这些值，例如：

| 原版配置 | 生成间隔 | 同时追踪上限 | 本轮总量 |
|---|---:|---:|---:|
| 普通试炼僵尸 | 20 刻（1 秒） | `⌊3+0.5A⌋` | `6+2A` |
| 普通试炼旋风人 | 20 刻（1 秒） | `⌊1+0.5A⌋` | `2+A` |
| 普通试炼慢速远程骷髅 | 160 刻（8 秒） | `4+2A` | `6+2A` |

出处：[配额公式](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerConfig.java:59)、JAR 内的[僵尸配置](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/data/minecraft/trial_spawner/trial_chamber/melee/zombie/normal.json)、[旋风人配置](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/data/minecraft/trial_spawner/trial_chamber/breeze/normal.json)、[慢速远程配置](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/data/minecraft/trial_spawner/trial_chamber/slow_ranged/skeleton/normal.json)。不祥试炼使用另一套配置，也不能统一套用上述数值。

试炼笼的具体计时与失败行为是：

- 每 **20 刻**扫描一次玩家；新增登记玩家时，将下一次生成时间至少推迟至当前游戏时间 **40 刻之后**。
- 使用绝对游戏时间 `nextMobSpawnsAt`。成功生成才增加本轮计数、登记实体 UUID，并设置下一次生成时间。
- 碰撞、视线、生成规则或实体加载等导致生成失败时，不消耗配额，也不设置新的生成间隔；条件仍满足时，下一 tick 再尝试一次。
- 达到同时追踪上限时等待。追踪实体死亡、查找不到、离开维度或距离笼子超过 **47 格**时会解除追踪，并重新等待一个配置间隔；解除追踪不会扣回“本轮已生成总数”。
- 达到本轮总量且追踪实体全部清空后，进入奖励流程和默认 **36,000 刻（30 分钟）**冷却。因此，玩家离开后继续生成也不是无限生成。

依据：[玩家扫描与生成资格](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerStateData.java:112)、[成功后才更新计数和时间](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerState.tickAndGetNext.java:58)、[生成失败返回分支](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawner.java:168)、[解除追踪逻辑](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawner.java:278)。

**适用范围与不确定性：**以上为该版 JAR 的静态代码和原始 JSON 核查，没有启动游戏实测；相关反编译结果未报告告警。结论假定生成规则允许、区块持续运算。试炼计时使用游戏时间，因此区块停止运算期间期限可能经过，但不会在卸载期间生成，也不会恢复时补刷所有错过的次数。版本信息、JAR SHA-256 和反编译来源保存在[证据清单](/private/tmp/hardrush-mc-optimization-20261025/runs/run-23/attempt-01/workspace/.local/spawner-26.3-evidence/provenance.json)中。