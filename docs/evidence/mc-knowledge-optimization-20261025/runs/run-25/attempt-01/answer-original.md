**普通刷怪笼：附近没有合格玩家后停止刷怪，倒计时暂停。试炼刷怪笼：挑战已经激活后，玩家离开探测范围仍可继续刷怪，但所在区块必须继续执行方块实体 tick，且受本轮数量限制。**

本次核验的是 **Java 正式版 26.3 原版服务端 JAR**：`version.json` 标记 `stable: true`，JAR SHA-256 为 `a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6`，本地工具确认其与服务端 bundle 一致。[版本文件](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/version.json)、[官方版本页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)

下表的秒数按 **20 TPS** 换算；试炼列先列出**未被具体配置覆盖时的代码默认值**。

| 项目 | 普通刷怪笼 | 试炼刷怪笼 |
|---|---|---|
| 玩家激活／探测距离 | 距方块中心**严格小于 16 格**；存活且非旁观者，创造玩家也可激活；无需视线 | 默认 **14 格**，按玩家与笼的方块位置判距；排除创造、旁观者；首次登记玩家需要视线 |
| 玩家离开后 | 无其他合格玩家时，**暂停剩余 `Delay`**；回来后接着倒数 | 已激活的挑战继续；登记人数不会因玩家离开而减少 |
| 刷怪延迟 | 初始 `Delay=20` tick；重置时随机取 **200～799 tick**，即约 10～40 秒 | 成功生成后默认间隔 **40 tick（2 秒）**；登记新玩家时，下一次生成至少推迟到当前时间后 40 tick |
| 一次尝试数量 | 每批最多 **4 次尝试**，不保证成功 4 只 | 每个满足条件的 tick **尝试 1 次，成功生成 1 个主体实体** |
| 数量限制 | 默认附近同类达到 **6** 时停止本批并重置延迟 | 不使用普通笼的“附近同类 6 只”规则；按本笼追踪的生物数限制 |
| 试炼默认数量 | — | 登记人数为 `n` 时，本轮总量 **`6+2(n−1)`**，同时追踪上限 **`2+(n−1)`** |

普通笼的“同类”具体按**相同运行时实体类**统计，检查区域为笼方块包围盒向各方向扩张 4 格，即 **9×9×9**；它不是全局怪物上限。依据：[普通笼参数与生成流程](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:43)、[普通笼玩家检查](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/EntityGetter-hasNearbyAlivePlayer.java:11)、[试炼玩家探测](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/PlayerDetector.java:26)、[试炼数量默认值](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerConfig.java:109)。

**计时和生成失败的处理：**

- **普通笼：**`Delay>0` 时只减计时；进入 tick 时已为 0 才开始本批尝试。若全部因碰撞、光照或其他生成条件不满足而失败，不重抽延迟，保持到期状态，**下一有效 tick 再尝试最多 4 次**。本批至少成功一次，就重抽 200～799 tick。检查到附近同类达上限、实体类型无效、实体加载失败或加入世界失败时，也会重抽延迟并提前结束本批。`Delay=-1` 则先初始化延迟。[`BaseSpawner.serverTick` 与 `delay`](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:95)
- **试炼笼：**用世界时间与 `nextMobSpawnsAt` 比较。到期且未达同时数量上限，才调用一次生成；生成失败时不增加已生成数量，也不推进截止时间，因此条件仍满足时，**下一 tick 再试**。达到本轮总量后不再补充，等待追踪生物全部清空，再发奖励并进入默认 36000 tick（30 分钟）冷却。[挑战状态流程](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerState-tickAndGetNext.java:38)、[到期与数量判定](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerStateData.java:104)
- **试炼笼追踪数量**不是附近所有同类数量。追踪实体死亡、查无实体、离开本维度或距笼超过 47 格时会移出记录；发生移除时，也会把下一次生成时间设为当前时间加配置间隔。[`tickServer` 与追踪移除条件](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawner.java:278)

**自然生成的试炼密室会覆盖默认参数。**例如以下普通、非不祥配置；`n` 为本轮已登记玩家数：

| 原版配置示例 | 生成间隔 | 同时上限 | 本轮总量 |
|---|---:|---:|---:|
| [僵尸近战配置](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/data/minecraft/trial_spawner/trial_chamber/melee/zombie/normal.json) | 20 tick | `⌊3+0.5(n−1)⌋` | `6+2(n−1)` |
| [慢速远程骷髅配置](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/data/minecraft/trial_spawner/trial_chamber/slow_ranged/skeleton/normal.json) | 160 tick | `4+2(n−1)` | `6+2(n−1)` |
| [旋风人配置](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/data/minecraft/trial_spawner/trial_chamber/breeze/normal.json) | 20 tick | `⌊1+0.5(n−1)⌋` | `2+(n−1)` |

不祥试炼使用独立的 `ominous_config`，数值可能不同，但玩家离开后继续挑战、每次尝试一个和失败重试的核心流程相同。[配置选择与生成实现](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawner.java:89)

最后，“玩家离开探测范围”与“区块停止 tick”必须区分：**区块卸载或不再满足方块实体 ticking 条件时，两种笼都不会生成实体**；仅仅区块仍被加载并不足够。试炼的截止时间使用世界时间，恢复 tick 后若已到期即可继续尝试，不会一次性补刷离开期间的批次。[区块 ticking 门槛](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/LevelChunk-isTicking.java:22)

以上是指定 JAR 的静态实现结论，**未做游戏运行验证**；已有存档参数及具体试炼配置应以实际保存值为准。本次生成的[出处、哈希及查询参数](/private/tmp/hardrush-mc-optimization-20261025/runs/run-25/attempt-01/workspace/.local/spawner-26.3-evidence/provenance.json)可用于复核。