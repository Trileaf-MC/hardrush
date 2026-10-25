**普通刷怪笼：所有合格玩家离开激活范围后，停止生成，倒计时也暂停。试炼刷怪笼：已经激活的一轮可以在玩家离开后继续生成，但仍受并发数量、本轮总量及生成条件限制。** 两者都要求所在位置仍进行方块实体更新；区块卸载后不会继续刷怪。[普通笼判定](sources/.local/spawner-26.3-evidence/BaseSpawner.java:95)、[试炼 ACTIVE 状态](sources/.local/spawner-26.3-evidence/TrialSpawnerState.java:38)、[区块更新门槛](sources/.local/spawner-26.3-evidence/LevelChunk-isTicking.java:23)

本次核对的是 **Java Edition 26.3 正式版原版服务端**。本地服务端包 SHA-1 与[官方发行页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)下载链接的对象哈希一致，内部 JAR 也通过包内 SHA-256 校验；[版本、哈希和复核命令已保存](sources/.local/spawner-26.3-evidence/provenance.json)。

**普通刷怪笼**

| 项目 | 26.3 默认行为 |
|---|---|
| 玩家激活距离 | 玩家位置到笼子方块中心的三维距离 **严格小于 16 格**；要求存活、非旁观者，创造模式也能激活，不要求视线 |
| 初始延迟 | `Delay=20 tick` |
| 后续随机延迟 | `MinSpawnDelay=200`、`MaxSpawnDelay=800`；实际抽取 **200～799 tick**，即约 10～40 秒 |
| 一轮尝试数量 | `SpawnCount=4`，最多尝试 4 次，不保证成功生成 4 只 |
| 附近同类上限 | `MaxNearbyEntities=6`；默认在笼子方块包围盒向六面各扩 4 格的 **9×9×9** 区域内，统计与待生成实体**运行时类完全相同**的实体 |

以上数值及调用位置见 [BaseSpawner 默认字段与生成循环](sources/.local/spawner-26.3-evidence/BaseSpawner.java:43)；玩家筛选和严格距离比较见 [hasNearbyAlivePlayer](sources/.local/spawner-26.3-evidence/EntityGetter-hasNearbyAlivePlayer.java:12)。

计时和失败处理分三种情况：

- **玩家离开：**保留当前 `Delay`，玩家回来后接着倒数。区块保持加载也不能绕过玩家距离要求。
- **普通生成条件失败：**碰撞、光照或生物生成规则等导致本轮全部失败时，不重置延迟；到期状态下，下一个 tick 再尝试最多 4 次。
- **重置延迟：**本轮至少成功一次，会重新抽取延迟；若发现附近同类已达 6，或遇到实体类型无效、实体加载失败、加入世界失败，也会立即重置并结束本轮。

源码是正数 `Delay` 每 tick 减一，减到 0 后的下一 tick 才进入尝试分支；上述 200～799 是写入计时器的值。[完整计时与失败分支](sources/.local/spawner-26.3-evidence/BaseSpawner.java:95)

**试炼刷怪笼**

| 项目 | 26.3 代码缺省值／行为 |
|---|---|
| 玩家检测范围 | **14 格**，按玩家与笼子的方块坐标判距；排除创造和旁观模式 |
| 检测频率 | 每 **20 tick** 扫描一次；首次登记玩家要求视线，已有登记玩家后追加玩家不要求视线 |
| 登记玩家后的缓冲 | 下一次生成时间至少推迟到当前时间 **40 tick** 后 |
| 成功生成间隔 | 缺省 `ticks_between_spawn=40 tick`；自然生成配置经常覆盖它 |
| 一次尝试数量 | 每个满足条件的 tick **尝试 1 只** |
| 数量限制 | 统计本笼记录的 `current_mobs`，没有普通笼那种“附近任意同类达到 6”的检查 |
| 缺省单人配额 | 同时 **2 只**、本轮总计 **6 只**；每多登记一名玩家分别增加 **1、2** |

缺省配置见 [TrialSpawnerConfig](sources/.local/spawner-26.3-evidence/TrialSpawnerConfig.java:109)；14 格配置见 [FullConfig](sources/.local/spawner-26.3-evidence/TrialSpawner.java:395)；检测条件与缓冲见 [PlayerDetector](sources/.local/spawner-26.3-evidence/PlayerDetector.java:26)、[tryDetectPlayers](sources/.local/spawner-26.3-evidence/TrialSpawnerStateData.java:124)。

**“代码缺省”不等于自然试炼密室的实际配置。** 26.3 原版 JAR 中，非灾厄试炼的配置包括：

| 自然生成类型 | 成功生成间隔 | 单人同时上限 | 单人本轮总量 |
|---|---:|---:|---:|
| 多数常规类型 | 20 tick／1 秒 | 3 | 6 |
| 幼年僵尸 | 20 tick／1 秒 | 2 | 6 |
| 旋风人 | 20 tick／1 秒 | 1 | 2 |
| 慢速远程类 | 160 tick／8 秒 | 4 | 6 |

多人增量及灾厄配置也有差异，不能统一套用上述数字；实际目标数量为配置的“基础值＋每额外玩家增量”，再向下取整。这里直接核对了全部 28 个原版配置：[逐项数值与资源路径](sources/.local/spawner-26.3-evidence/trial-config-values.json)。

试炼笼的计时与失败行为是：

- **玩家离开不取消登记，也不暂停已激活的一轮。** 因此仍可继续生成；若留下的怪占满并发名额，就停在上限处。
- 使用绝对游戏时间 `next_mob_spawns_at`。成功才增加本轮计数并设置下一次时间；位置、碰撞等导致生成失败时，不增加计数、不重设时间，条件仍满足则下一 tick 再试一次。
- 已记录怪物死亡、查找不到、换维度，或距笼子超过 **47 格**时，会解除追踪；发生解除追踪的 tick 会把下一次生成时间重设为“当前时间＋配置间隔”。
- 达成本轮总量且追踪怪物全部清空后，进入奖励流程；默认冷却目标为 **36,000 tick／30 分钟**。

这些行为见 [生成状态机](sources/.local/spawner-26.3-evidence/TrialSpawnerState.java:38)、[时间与并发判定](sources/.local/spawner-26.3-evidence/TrialSpawnerStateData.java:104)、[解除追踪逻辑](sources/.local/spawner-26.3-evidence/TrialSpawner.java:278)。

以上是针对匹配官方包的 **26.3 字节码所作静态核对**，没有启动游戏验证；秒数按 20 TPS 换算。结论适用于默认原版机制，具体笼子的 NBT、原版数据配置和游戏规则仍可能改变参数或禁止生成。