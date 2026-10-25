**普通刷怪笼会停；已激活的试炼刷怪笼可以继续。**这里“离开”指所有符合条件的玩家离开检测范围；两者都必须处于服务端持续执行方块实体 tick 的区块中，区块卸载或停止 tick 后都不会继续生成。

以下依据 **Java Edition 26.3 正式版原版服务端 JAR**，已核对 `version.json` 及服务端包 SHA-256；不是套用旧版结论。[官方版本说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)、[本次本地证据与校验信息](sources/.local/spawner-26.3-evidence/provenance.json)。

| 项目 | 普通刷怪笼 | 试炼刷怪笼 |
|---|---|---|
| 默认玩家距离 | **16 格**；玩家位置到笼子中心的三维距离严格小于 16 | **14 格**；代码按玩家与笼子的方块坐标计算三维距离 |
| 玩家条件 | 存活、非旁观者；创造玩家也可激活，无视线要求 | 生存／冒险玩家；首次登记需要视线，已有登记玩家后，追加登记不要求视线 |
| 玩家离开后 | 无合格玩家在范围内时，**停止生成并冻结剩余倒计时**；回来后续计 | 进入 `ACTIVE` 后，**不要求玩家持续留在范围内**，离开也不会从本轮登记人数中扣除 |
| 默认生成延迟 | 初始 `Delay=20`；常规重置值为 **200～799 tick**，最大配置值 800 不包含在抽取范围内 | 配置字段默认 **40 tick**；首次登记及新增玩家会把下次生成推迟至至少当前时间后 **40 tick** |
| 一次尝试数量 | 每轮最多 **4 次**生成尝试，不保证成功 4 只 | 每个满足条件的 tick 调用 **1 次**生成尝试，成功后设置下一次时间 |
| 数量限制 | 默认附近同类上限 **6** | 没有普通笼子的“附近同类 6 只”规则；使用本笼子的**追踪存活数量上限＋本轮生成总量** |

普通笼子的数值、激活与生成流程见 [`BaseSpawner`](sources/.local/spawner-26.3-evidence/BaseSpawner.java:44)，玩家筛选见 [`hasNearbyAlivePlayer`](sources/.local/spawner-26.3-evidence/EntityGetter.hasNearbyAlivePlayer.java:12)。试炼的距离默认值见 [`FullConfig`](sources/.local/spawner-26.3-evidence/TrialSpawner.java:395)，玩家登记条件见 [`PlayerDetector`](sources/.local/spawner-26.3-evidence/PlayerDetector.java:26) 与 [`tryDetectPlayers`](sources/.local/spawner-26.3-evidence/TrialSpawnerStateData.java:124)。

普通刷怪笼的计时与失败行为：

- `Delay>0` 时每个活跃 tick 减 1；减到 0 后，**下一个活跃 tick** 才开始尝试。`Delay=-1` 会先重新抽取延迟。
- 一轮中至少成功生成 1 只，轮末便重新抽取延迟。
- 如果全部因碰撞、光照／生成规则、空间阻挡等位置条件失败，**不重置延迟**，保持 0，下一活跃 tick 再尝试最多 4 次。
- 执行到附近数量检查且已经达到 6，或遇到实体类型无效、实体加载失败、加入世界失败，则**立即重置延迟并结束本轮**。因此不能笼统说“生成失败都每 tick 重试”。
- “附近”默认是笼子方块包围盒向各方向扩展 4 格，即 **9×9×9** 区域，统计碰撞箱与其相交、Java 实体类完全相同的实体；不限于这一个笼子生成的怪。

以上分支均可在 [`BaseSpawner.serverTick / delay`](sources/.local/spawner-26.3-evidence/BaseSpawner.java:95) 复核。

试炼刷怪笼需要区分**字段默认值**与**试炼密室实际配置**。令 `a=本轮登记人数−1`，下表公式结果向下取整：

| 非不祥配置 | 成功后的生成间隔 | 同时追踪上限 | 本轮总量 |
|---|---:|---:|---:|
| 未覆写的字段默认值 | 40 tick | `2+a` | `6+2a` |
| 密室普通配置，以僵尸为例 | 20 tick | `3+0.5a` | `6+2a` |
| 密室慢速远程，以骷髅为例 | 160 tick | `4+2a` | `6+2a` |
| 密室旋风人 | 20 tick | `1+0.5a` | `2+a` |

出处：[`TrialSpawnerConfig` 默认值与计算方法](sources/.local/spawner-26.3-evidence/TrialSpawnerConfig.java:59)，以及 JAR 内的[僵尸配置](sources/.local/spawner-26.3-evidence/zombie.normal.json)、[慢速骷髅配置](sources/.local/spawner-26.3-evidence/slow_ranged_skeleton.normal.json)、[旋风人配置](sources/.local/spawner-26.3-evidence/breeze.normal.json)。不祥试炼共用状态流程，但选择独立的 `ominous` 配置，不能直接套用这张表的数值。

试炼的计时与失败行为：

- 使用世界游戏时间与 `nextMobSpawnsAt` 比较；达到时间、未满同时追踪上限、未完成本轮总量时才尝试。
- **成功才增加本轮生成数、登记实体 UUID，并更新下次生成时间。**位置碰撞、视线、生成规则、实体加载或加入世界失败，均不消耗本轮名额，也不设置成功间隔；通常下一 tick 再试。
- 同时追踪上限满了就等待。追踪实体死亡、无法查到、跨维度或距笼子超过 47 格时会解除追踪；发生解除追踪时，又会设置一次配置中的生成间隔。
- 所以玩家走开后可能继续刷到同时上限，然后等待；**不是无限连续刷怪**。已经达到本轮总量后，也不会继续补怪。

出处：[`ACTIVE` 状态流程](sources/.local/spawner-26.3-evidence/TrialSpawnerState.tickAndGetNext.java:38)、[`spawnMob`](sources/.local/spawner-26.3-evidence/TrialSpawner.java:168)、[追踪清理与计时更新](sources/.local/spawner-26.3-evidence/TrialSpawner.java:278)。

**适用范围与不确定性：**上述为 26.3 原版源码与内置数据的静态核对，未启动游戏验证。秒数按正常 **20 TPS** 换算；原版 NBT、数据包、难度和游戏规则可以改变配置或禁止生成。“区块仍加载”也不一定等于持续 tick，实际方块实体 tick 还有[区块状态检查](sources/.local/spawner-26.3-evidence/LevelChunk.isTicking.java:23)。本次未读取既有调查报告，也未调查模组行为。