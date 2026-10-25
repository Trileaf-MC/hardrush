**Java 版 26.3 原版中：普通刷怪笼在所有有效玩家离开激活范围后，会暂停倒计时和刷怪；试炼刷怪笼一旦激活，玩家离开本身不会停止本轮生成。** 后者仍受本轮总量、同时存活数量和生成条件限制，并不会无限刷怪。

两者都要求所在位置继续执行方块实体 tick；区块卸载或停止 tick 后，都不会在后台生成实体。“仍加载”也不一定代表仍执行 tick。见本次提取的 [`Level.tickBlockEntities()`](sources/.local/spawner-evidence-26.3/Level-tickBlockEntities.java:22)。

以下秒数按 **20 TPS** 换算，假定游戏规则和难度允许相应生成。

| 项目 | 普通刷怪笼 | 试炼刷怪笼 |
|---|---|---|
| 默认玩家距离 | **16 格**，从笼子中心计算，代码要求距离严格小于 16；存活、非旁观玩家有效，创造玩家也有效 | **14 格**；默认排除创造和旁观玩家。代码按玩家与笼子的**方块坐标距离**检测 |
| 玩家检测用途 | 每个服务端 tick 都是计时、生成的前置条件 | 用于登记参与者；首次登记需要视线，已有参与者后新增登记不要求视线。每 20 tick 扫描一次 |
| 玩家离开后 | 没有其他有效玩家时，`Delay` 保持原值；回来后续计 | 已登记玩家不会因走远被移除；已激活的本轮仍可继续生成 |
| 默认刷怪延迟 | 初始 `Delay=20`；重置时抽取 **200–799 tick**，约 10–40 秒 | 代码配置默认 `ticks_between_spawn=40`，即 2 秒；**试炼之地实际配置通常覆盖为 20 tick，慢速远程为 160 tick** |
| 一次尝试数量 | 一轮最多尝试 **4 次**，不保证生成 4 只 | 每个符合条件的 tick 调用 **1 次**生成尝试，不是普通笼的四次循环 |
| 附近数量限制 | 默认 **6 只**；具体检测区域与类型见下文 | 使用本笼登记的在场怪物数量和配置上限，不采用普通笼的“附近同类 6 只”规则 |

普通笼出处：[`BaseSpawner` 默认字段、`serverTick()`、`delay()`](sources/.local/spawner-evidence-26.3/BaseSpawner.java:43)、[`EntityGetter.hasNearbyAlivePlayer()`](sources/.local/spawner-evidence-26.3/EntityGetter-hasNearbyAlivePlayer.java:11)。试炼笼出处：[`FullConfig` 的距离默认值](sources/.local/spawner-evidence-26.3/TrialSpawner.java:395)、[`PlayerDetector`](sources/.local/spawner-evidence-26.3/PlayerDetector.java:26)、[`tryDetectPlayers()`](sources/.local/spawner-evidence-26.3/TrialSpawnerStateData.java:124)。

普通刷怪笼的计时与失败行为，需要区分几种情况：

- `Delay>0` 时只减一；**减到 0 的那个 tick 不生成，下一 tick 才尝试**。因此上表的 200–799 是重置的计时字段值；连续激活、立即成功时，两轮尝试相隔 201–800 tick。
- 一轮四次尝试中，**至少成功一次**，轮末就重新抽取延迟。
- 若全部因碰撞、光照或生物生成规则等位置条件失败，且没有触发下面的提前返回分支，**不重置延迟，下一个有效 tick 再试**。
- 检测到附近数量已达上限，或出现实体类型解析失败、实体加载失败、加入世界失败时，**立即重置延迟并结束本轮**。

这里的“附近同类上限 6”具体是：在笼子方块的 `1×1×1` 包围盒向各方向扩展默认 `SpawnRange=4`，即 **9×9×9** 的检测盒内，统计与待生成实体**运行时类完全相同**的实体。它不是自然刷怪的全局怪物上限，也不只统计这只笼子生成的怪物。见 [`BaseSpawner.serverTick()`](sources/.local/spawner-evidence-26.3/BaseSpawner.java:95)。

试炼刷怪笼则按“已登记参与者”和“本笼追踪怪物”推进：

- 设额外参与者数为 `a=已登记人数−1`。**代码缺省配置**为：本轮总量 `6+2a`，同时存活上限 `2+a`；配置计算统一向下取整。
- **自然生成配置会覆盖缺省值**：例如普通僵尸试炼笼为间隔 20 tick、总量 `6+2a`、同时上限 `floor(3+0.5a)`；普通旋风人为间隔 20 tick、总量 `2+a`、同时上限 `floor(1+0.5a)`。不祥试炼使用各自的 `ominous_config`，不能统一套用上述缺省值。
- 新登记玩家会把下次生成时刻推迟至**至少当前时间后 40 tick**；成功生成后，才设置下一次间隔。
- 碰撞、视线、生成规则等导致生成失败时，**不增加已生成总量，也不推进下次生成时刻**；条件仍满足时，下一 tick 再尝试。
- 达到同时上限时等待。追踪怪物死亡、找不到实体、换维度或离笼子的方块坐标距离超过 **47 格**，会解除追踪，并把下次生成时刻设为“当前时间＋配置间隔”。
- 本轮总量已经生成完后，不再补怪；等追踪列表清空，才进入奖励流程及默认 **36,000 tick（30 分钟）**冷却。玩家走远不会直接清空本轮参与者记录。

这些行为可核对 [`TrialSpawnerConfig`](sources/.local/spawner-evidence-26.3/TrialSpawnerConfig.java:59)、[`ACTIVE` 状态逻辑](sources/.local/spawner-evidence-26.3/TrialSpawnerState.java:83)、[`生成就绪判断`](sources/.local/spawner-evidence-26.3/TrialSpawnerStateData.java:112)、[`追踪清理逻辑`](sources/.local/spawner-evidence-26.3/TrialSpawner.java:278)，以及 JAR 内的[僵尸普通配置](sources/.local/spawner-evidence-26.3/data/minecraft/trial_spawner/trial_chamber/melee/zombie/normal.json)、[旋风人普通配置](sources/.local/spawner-evidence-26.3/data/minecraft/trial_spawner/trial_chamber/breeze/normal.json)。

版本依据是本地原版服务端 JAR 的 [`version.json`](sources/.local/spawner-evidence-26.3/version.json)：`id=26.3`、`stable=true`，构建日期为 2026-09-15，与[官方 26.3 发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)一致。JAR 的完整 SHA-256 和反编译器版本保存在[取证元数据](sources/.local/spawner-evidence-26.3/provenance.json)。

**本次结论来自该版本 JAR 的静态代码与数据核对，未运行游戏验证。** 存档中已有的计时值、NBT 和配置可能覆盖默认值，实际秒数也受 TPS 影响；结论不外推到其他版本或修改过的服务端。