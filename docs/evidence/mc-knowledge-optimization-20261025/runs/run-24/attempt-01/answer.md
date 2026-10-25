**普通刷怪笼：附近没有合格玩家后，停止刷怪并暂停倒计时。试炼刷怪笼：已经激活的试炼不会仅因玩家离开而停止，但仍受本轮数量、并存上限和区块运行状态限制。**

这里的“离开”指所有合格玩家都离开范围；只要还有其他合格玩家，普通刷怪笼仍可工作。

本次核对的是 **Minecraft Java Edition 26.3 正式版原版服务端 JAR**，没有用近似版本代替。版本也可对照[官方 26.3 发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。下述秒数按正常 **20 tick/秒**换算。

| 项目 | 普通刷怪笼 | 试炼刷怪笼：代码缺省配置 |
|---|---|---|
| 玩家激活距离 | 距方块中心 **小于 16 格**；存活、非旁观玩家，创造玩家也有效；不要求视线 | 默认 **14 格**，检测按玩家与刷怪笼的方块坐标距离判断；排除创造、旁观玩家；首次登记玩家要求视线 |
| 刷怪延迟 | 初始 `Delay=20`；重设时随机取 **200～799 tick**，通常概称约 10～40 秒 | 成功生成后默认间隔 **40 tick（2 秒）**；新登记玩家时，下一次生成至少推迟至当前时间＋40 tick |
| 一次尝试数量 | 每轮最多 **4 次尝试**，不保证成功生成 4 只 | 满足条件的每个 tick **尝试生成 1 只** |
| 附近同类／并存上限 | 默认 **6 只**；生成前检测到已达 6 只，就结束本轮并重设延迟 | 没有普通笼式的“附近同类 6 只”检查；限制的是**本刷怪笼登记的当前怪物数** |
| 试炼默认数量 | 不适用 | 单人本轮总量 **6**、同时存在 **2**；每新增登记玩家，总量加 **2**、并存上限加 **1** |

普通笼数值与流程见 [BaseSpawner](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:43>)；试炼缺省数值见 [TrialSpawnerConfig.Builder](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerConfig.java:109>)，玩家检测见 [PlayerDetector](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/PlayerDetector.java:26>)。

**普通刷怪笼的计时与失败行为**

- **玩家离开范围：**服务端不再递减 `Delay`，也不尝试生成；玩家回来后从剩余值继续。
- **倒计时：**`Delay>0` 时只减 1；减到 0 的这一 tick 不生成，下一 tick 才进入生成分支。`Delay=-1` 时先重设延迟。
- **至少成功一次：**这一轮结束后重设随机延迟。
- **整轮因空间碰撞、光照或其他生成条件失败：**不重设延迟，保持 0，下一 tick 再尝试，默认仍是一轮最多 4 次。
- **附近同类达上限：**立即重设延迟并返回。实体类型无效、实体加载失败、加入世界失败等分支也会重设延迟。

“附近同类”的准确口径是**相同运行时实体类**；默认检查刷怪笼方块包围盒向六面各扩展 4 格，即 **9×9×9** 的查询区域，按实体包围盒相交计数，且不限定怪物必须由该笼生成。完整分支见 [serverTick 与 delay](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/BaseSpawner.java:95>)。

**试炼刷怪笼的计时与失败行为**

试炼激活后，登记玩家不会因为走远就从名单移除；`ACTIVE` 状态也不要求玩家持续处于 14 格内。因此，**玩家离开后，只要刷怪笼仍被游戏更新，就可以继续生成，直到并存上限或本轮总量限制生效**，并非无限刷怪。见[玩家登记逻辑](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerStateData.tryDetectPlayers.java:23>)及[试炼状态机](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawnerState.tickAndGetNext.java:38>)。

- 它保存的是下一次生成的**游戏时间戳**，不是普通笼式的逐 tick 递减计数。
- 达到并存上限时等待空位；生成失败时不增加已生成数量，也不设置新的成功间隔，条件仍满足就下一 tick 重试一次。
- 怪物死亡、无法查到、换维度或离笼超过 **47 格**后会被取消跟踪；这次移除会把下一次生成时间设为“当前时间＋配置间隔”。
- 本轮总量生成完后，等待所有当前跟踪怪物清空，再进入奖励流程与冷却；默认冷却参数为 **36000 tick，30 分钟**。

这些流程见 [TrialSpawner](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/TrialSpawner.java:278>)。

**试炼的“代码缺省值”不能直接当作所有自然生成试炼笼的数值。**26.3 原版 JAR 自带配置会覆盖它们，例如：

| 原版配置 | 成功生成间隔 | 单人总量／并存上限 | 每名额外玩家增加总量／并存上限 |
|---|---:|---:|---:|
| 普通僵尸试炼 | 20 tick | 6／3 | 2／0.5 |
| 普通旋风人试炼 | 20 tick | 2／1 | 1／0.5 |
| 普通慢速远程骷髅试炼 | 160 tick | 6／4 | 2／2 |

人数计算后**向下取整**。对应 JAR 原始数据副本：[僵尸](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/trial_chamber_melee_zombie_normal.json>)、[旋风人](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/trial_chamber_breeze_normal.json>)、[慢速远程骷髅](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/trial_chamber_slow_ranged_skeleton_normal.json>)。不祥试炼也使用上述状态机制，但采用自己的数量配置。

两类刷怪笼都需要所在区块实际获得方块实体更新；**区块卸载或停止更新时，不会继续实际生成**。试炼恢复更新后会比较当前游戏时间与时间戳，但不会按离开期间错过的间隔批量补刷。

本次结论来自静态数据、反编译代码及普通笼构造器字节码核对，未启动游戏做运行时验证。来源 JAR 的 SHA-256 为 `a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6`；查询、源码哈希与原始证据入口保存在[复核清单](</private/tmp/hardrush-mc-optimization-20261025/runs/run-24/attempt-01/workspace/.local/spawner-26.3-evidence/manifest.json>)。数值不应直接外推到其他版本、修改过 NBT 的刷怪笼或自定义数据包配置。