**普通刷怪笼：所有有效玩家离开激活范围后，停止刷怪，倒计时冻结；玩家回来后接着计时。试炼刷怪笼：激活后，玩家离开仍可继续本轮，但受同时存活上限、总配额和区块 tick 限制。**

核查版本为 **Java Edition 26.3 正式版**，依据本地原版服务端 JAR 的源码、数据与字节码；版本信息见 [JAR 内的 version.json](sources/.local/spawner-26.3-evidence/version.json)，亦有[官方发行说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。以下秒数按正常 **20 TPS** 换算。

| 项目 | 普通刷怪笼 | 试炼刷怪笼：代码缺省配置 |
|---|---|---|
| 玩家激活距离 | `RequiredPlayerRange=16`；笼中心到玩家位置的三维距离 **<16 格** | `required_player_range=14`；实现按玩家所在方块与笼方块的距离 **<14 格** 判断 |
| 有效玩家 | 活着的非旁观者；创造模式也有效；不要求视线 | 默认生存／冒险模式；首次发现玩家要求视线 |
| 玩家离开 | 范围内无人时，不减少 `Delay`，也不尝试生成 | 已登记玩家不会因离开而从本轮人数中移除；可继续生成 |
| 延迟 | 初始 `Delay=20 tick`；重置时随机取 **200～799 tick**，约 10～40 秒，800 不包含在内 | 首次登记玩家后等待至少 **40 tick**；缺省成功生成间隔为 **40 tick／2 秒** |
| 一轮尝试数量 | `SpawnCount=4`：最多尝试 4 次，不保证成功 4 只 | 每个满足条件的 tick 尝试生成 **1 只** |
| 数量限制 | `MaxNearbyEntities=6` | 单人缺省同时追踪 **2 只**，本轮累计生成 **6 只** |
| 多人影响 | 不增加上述数量 | 每多登记一名玩家，缺省总数 +2、同时上限 +1 |
| 本轮结束 | 没有试炼式总配额，满足条件可反复循环 | 达总配额且追踪怪物清空后发奖励；缺省冷却 **36,000 tick／30 分钟**，包含发奖时间 |

普通笼的“附近同类”按**相同 Java 实体类**计数，不要求由这个笼生成；默认检测盒是笼方块向六面各扩张 4 格，尺寸为 **9×9×9**，不要与生成位置范围混淆。出处：[BaseSpawner：默认值、serverTick、delay](sources/.local/spawner-26.3-evidence/BaseSpawner.java:44)、[普通笼玩家判定](sources/.local/spawner-26.3-evidence/EntityGetter.hasNearbyAlivePlayer.java:11)。

试炼笼没有普通笼那种“附近同类达到 6”的检查；限制的是**本笼 UUID 集合中仍在追踪的实体**。怪物死亡、找不到实体、跨维度或离笼超过 47 格，会被取消追踪；发生取消追踪时，下次生成时间会推迟一个配置间隔。人数与时序见 [TrialSpawnerStateData](sources/.local/spawner-26.3-evidence/TrialSpawnerStateData.java:112)，追踪清理见 [TrialSpawner.tickServer](sources/.local/spawner-26.3-evidence/TrialSpawner.java:278)。

**天然试炼密室并非全部使用上表的代码缺省值。** 正常、非不祥试炼的主要配置如下；`a` 是本轮额外登记人数，同步上限向下取整：

| 天然配置 | 成功生成间隔 | 同时追踪上限 | 本轮总数 |
|---|---:|---:|---:|
| 多数近战／远程笼，例如普通僵尸 | 20 tick | `⌊3+0.5a⌋` | `6+2a` |
| 幼年僵尸 | 20 tick | `⌊2+0.5a⌋` | `6+2a` |
| 慢速远程笼 | 160 tick | `4+2a` | `6+2a` |
| 旋风人 | 20 tick | `⌊1+0.5a⌋` | `2+a` |

依据为 [TrialSpawnerConfig 缺省值与计算方法](sources/.local/spawner-26.3-evidence/TrialSpawnerConfig.java:59)、[原版天然配置注册](sources/.local/spawner-26.3-evidence/TrialSpawnerConfigs.bootstrap.java:16)，并直接核对了 JAR 中的[僵尸配置](sources/.local/spawner-26.3-evidence/zombie.normal.json)。不祥试炼使用独立的 `ominous_config`，不能直接套用此表的配额。

**生成失败与计时的关键区别：**

- **普通笼：**如果整轮 4 次都只是因碰撞、光照或生成规则不满足而失败，`Delay` 保持为 0，下一有效 tick 再尝试。只要有成功生成，轮末就重新抽延迟；触发附近上限、实体类型无效、实体加载失败或加入世界失败，则立即重置延迟并结束本轮。`Delay=-1` 也会重新抽延迟。[对应分支](sources/.local/spawner-26.3-evidence/BaseSpawner.java:95)
- **试炼笼：**生成失败返回空结果，**不会因此重新等待 20／40／160 tick**；其他条件不变时，下一 tick 再试。只有成功生成的回调才增加已生成数、设置下一次时间。此点已用[状态机字节码](sources/.local/spawner-26.3-evidence/TrialSpawnerState.tickAndGetNext.bytecode.txt:130)及[成功回调字节码](sources/.local/spawner-26.3-evidence/TrialSpawnerState.spawnSuccess.bytecode.txt:3)复核。
- **区块停止 tick：**两种笼都不会生成。试炼记录的是世界 `gameTime` 截止时间；若卸载期间世界时间继续推进，回载时可能已经到期，但不会补刷期间积欠的批次。[区块 tick 门槛](sources/.local/spawner-26.3-evidence/LevelChunk.isTicking.java:22)、[试炼状态机](sources/.local/spawner-26.3-evidence/TrialSpawnerState.tickAndGetNext.java:38)

适用前提是原版配置、正常运行的世界及允许刷怪的规则；试炼还要求非和平难度。以上是 **26.3 的静态核查结论，未启动游戏实测**，不外推到其他版本或修改后的数据包。JAR 完整 SHA-256、查询参数和各证据文件校验值保存在[证据清单](sources/.local/spawner-26.3-evidence/manifest.json)。