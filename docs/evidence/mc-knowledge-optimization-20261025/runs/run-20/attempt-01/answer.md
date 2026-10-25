**Java 26.3 原版中：普通刷怪笼在附近没有合格玩家时停止刷怪并暂停倒计时；试炼刷怪笼已经激活后，玩家离开探测范围仍可继续刷怪。** 两者都需要所在区块继续执行方块实体 tick；区块卸载后不会继续生成。

本次核对的是 **Java Edition 26.3 正式版**原版服务端 JAR：`version.json` 标记 `stable=true`，构建时间为 2026-09-15；工具校验其与服务端 bundle 内记录的 SHA-256 一致。版本亦可对照[官方发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。以下秒数按 20 TPS 换算。

| 项目 | 普通刷怪笼 | 试炼刷怪笼 |
|---|---|---|
| 默认玩家距离 | **16 格**，从刷怪笼中心计算三维距离，代码要求距离严格小于 16；存活且非旁观者，创造玩家也可激活，无视线要求 | **14 格**，按玩家与刷怪笼的方块坐标计算三维距离；排除创造、旁观者；首次登记玩家需要视线 |
| 玩家离开后 | 若范围内已无其他合格玩家：停止尝试，保留剩余倒计时；回来后继续 | 已激活则继续；已登记玩家不会仅因离开范围而被移除 |
| 默认生成延迟 | 初始 `Delay=20`；重置时抽取 **200～799 tick**，约 10～39.95 秒，800 不包含 | 配置缺省 `ticks_between_spawn=40`，即 2 秒；首次或新增玩家登记会将下次生成时间推迟至至少当前时间＋40 tick |
| 一次尝试数量 | 每批最多 **4 次**，不是保证生成 4 只 | 每个满足生成条件的 tick **尝试 1 只** |
| 数量上限 | 附近同类默认 **6 只** | 没有普通笼的“附近同类 6 只”检查；限制本笼追踪的存活怪物数量及本轮累计生成量 |

普通笼的默认值、玩家门控和批次循环见 [BaseSpawner](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:42>)；试炼笼的登记与延迟见 [tryDetectPlayers](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerStateData.java:124>)、[玩家筛选器](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/PlayerDetector.java:26>)。

**普通刷怪笼的计时与失败行为**

- `Delay>0` 时每个激活 tick 减一；**减到零后的下一 tick**才执行生成尝试。
- 一批中至少成功生成一只：批次结束后重新抽取延迟。
- 若四次都因位置、碰撞、光照或实体生成条件等失败，且没有触发下述提前退出分支：**不重抽延迟**，保持零，在下一个激活 tick 再尝试一批。
- 检查发现附近同类已达 6 只：**立即结束本批并重抽延迟**。实体类型无法解析、实体加载失败、加入世界失败，也会重抽延迟并退出。
- “附近”是刷怪笼所在的单位方块包围盒向六面扩展 `SpawnRange=4`，即 **9×9×9** 的轴对齐范围；按实体包围盒相交查询。“同类”使用**完全相同的 Java 实体类**，不区分这些怪物由哪个刷怪笼生成。

这些分支均可在 [serverTick 与 delay](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:95>) 中复核。

**试炼刷怪笼的数量与失败行为**

设 `p` 为本轮已登记玩家数减一，最低取零。缺省配置为：

- 同时追踪上限：`floor(2 + 1×p)`。
- 本轮累计生成目标：`floor(6 + 2×p)`。
- 生成成功才增加计数，并设置 `nextMobSpawnsAt = 当前游戏时间 + 生成间隔`。位置、碰撞、视线、生成规则或加入世界失败时，不更新这一时间；若条件仍满足，下一个 tick 再尝试。
- 达到同时上限时等待空位；怪物死亡或被移出追踪集合时，会把下次生成时间设为当前时间＋生成间隔。移出条件包括实体无法找到、死亡、维度变化或离笼超过 **47 格**。
- 累计达到目标后不再补怪，等待追踪集合清空，再进入奖励流程；默认冷却长度 **36000 tick，即 30 分钟**。

因此，“玩家离开仍继续”不代表会无限刷怪：它仍受同时上限、累计目标和生成条件限制。依据：[缺省配置与计算公式](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerConfig.java:59>)、[ACTIVE 状态逻辑](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerState.tickAndGetNext.java:38>)、[怪物追踪清理](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawner.java:278>)。

**注意：试炼笼的“配置缺省值”不等于所有自然生成预设。** 26.3 原版试炼密室数据实际包括：

| 非不祥预设示例 | 生成间隔 | 同时上限 | 本轮累计目标 |
|---|---:|---:|---:|
| 僵尸近战 | 20 tick | `floor(3+0.5p)` | `6+2p` |
| 慢速骷髅远程 | 160 tick | `4+2p` | `6+2p` |
| 旋风人 | 20 tick | `floor(1+0.5p)` | `2+p` |

不祥试炼另选 `ominous` 配置，具体数量可能不同，但玩家离开后是否继续、成功后如何更新时间的机制相同。上述数值来自 JAR 的 `data/minecraft/trial_spawner/trial_chamber/…`；已保存[原版配置原文](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/trial-chamber-configs.json>)供复核。

最后，**区块加载与玩家激活是两项独立条件**：保持区块运行不能替代普通笼的 16 格玩家条件。试炼笼使用绝对游戏时间；停止 tick 期间不会生成，恢复后若时间已到则重新尝试，不会补发期间所有生成次数。[区块 tick 条件](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/LevelChunk.isTicking.java:23>)。

以上是对该 **26.3 JAR 的静态代码与数据核查**，未启动客户端或服务端做运行验证；关键方法反编译未报告告警。结论限于原版、默认相关游戏规则及所列配置，不外推到其他版本。[完整出处、JAR 哈希与反编译记录](</private/tmp/hardrush-mc-optimization-20261025/runs/run-20/attempt-01/workspace/.local/spawner-26.3-evidence/provenance.json>)。