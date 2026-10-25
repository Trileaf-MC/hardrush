**普通刷怪笼：附近没有合格玩家后，停止刷怪，倒计时也暂停。试炼刷怪笼：一旦激活，玩家离开检测范围仍可继续刷怪，但受本轮配额、同时存活数量等条件限制。** 两者都要求所在区块继续执行方块实体 tick；仅“区块已加载”并不充分。[普通实现](sources/.local/mc-knowledge/spawner-26.3-evidence.md:235)、[试炼状态机](sources/.local/mc-knowledge/spawner-26.3-evidence.md:402)、[区块执行条件](sources/.local/mc-knowledge/spawner-26.3-evidence.md:1481)

本次核对的是 **Java Edition 26.3 正式版原版服务端 JAR**，`version.json` 标记为 `26.3`、`stable: true`，工具报告通过服务端包内 SHA-256 校验；[官方发行页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)也确认该版本。以下秒数按 **20 TPS** 换算。

| 项目 | 普通刷怪笼 | 试炼刷怪笼 |
|---|---|---|
| 默认玩家激活距离 | 距方块中心 **严格小于 16 格**；存活、非旁观玩家，创造模式也能激活；无需视线 | **14 格**；默认排除创造、旁观玩家；首次登记需要视线，按玩家与刷怪笼的方块坐标判距离；每 20 tick 扫描一次 |
| 玩家离开后 | 若没有其他合格玩家，暂停计时与生成；回来后接着剩余时间 | `ACTIVE` 状态不再以附近是否有玩家作为刷怪门槛；离开的玩家仍保留在本轮登记集合中 |
| 默认延迟 | 初始 `Delay=20`；重置时随机取 **200～799 tick**，即倒计时约 10～40 秒 | 通用配置 `ticks_between_spawn=40`，即 2 秒；发现新参与玩家时，将下一次生成时间至少推迟至当前时间后 40 tick |
| 一次尝试数量 | 每轮最多 **4 次生成尝试**，不保证生成 4 只 | 每次满足条件的 tick **尝试生成 1 只** |
| 数量上限 | 默认附近 **6 个同类实体** | 没有普通刷怪笼式的“附近同类 6 个”检查；限制自身追踪的本轮怪物数 |
| 通用试炼配额 | — | 登记玩家数为 `p≥1` 时：同时上限 **2+(p−1)**，本轮总量 **6+2(p−1)** |

表中数值可核对 `BaseSpawner`、`EntityGetter.hasNearbyAlivePlayer`、`PlayerDetector`、`TrialSpawnerStateData` 和 `TrialSpawnerConfig`。[普通默认值与距离判断](sources/.local/mc-knowledge/spawner-26.3-evidence.md:34)、[普通玩家筛选](sources/.local/mc-knowledge/spawner-26.3-evidence.md:377)、[试炼登记与计时](sources/.local/mc-knowledge/spawner-26.3-evidence.md:623)、[试炼默认配置](sources/.local/mc-knowledge/spawner-26.3-evidence.md:874)、[试炼距离与检测器](sources/.local/mc-knowledge/spawner-26.3-evidence.md:1202)

**普通刷怪笼的计时和失败行为：**

- `Delay>0` 时只减 1；减到零的那个 tick 不生成，下一 tick 才尝试。`Delay=-1` 时先重置随机延迟。
- 附近上限检查的是刷怪笼方块包围盒向各方向扩展默认 4 格，形成 **9×9×9** 的检查盒；实现按实体的**精确 Java 类**计数，也会计入其他来源的同类实体。
- 一轮至少成功生成 1 只，轮末便重置延迟。
- 若全部尝试仅因碰撞、光照或其他生成条件失败，延迟保持 **0**，下 tick 再尝试最多 4 次。
- 若执行到检查时发现附近同类已达 6 个，或实体类型无效、实体加载失败、加入世界失败，则**立即重置延迟并结束本轮**。[完整生成分支](sources/.local/mc-knowledge/spawner-26.3-evidence.md:235)、[随机延迟公式](sources/.local/mc-knowledge/spawner-26.3-evidence.md:353)

**试炼刷怪笼的计时和失败行为：**

- 使用绝对游戏时间 `nextMobSpawnsAt`。时间已到且追踪数量低于上限才尝试；成功后增加本轮生成数，并设置下一次生成时间。
- 碰撞、视线、生成规则、实体加载或加入世界失败时，不增加计数，也不重新设置生成间隔；条件仍满足时，下 tick 重试。
- 同时数量达到上限时等待。被追踪怪物死亡、无法查到、跨维度或离刷怪笼超过 **47 格**后会解除追踪；发生解除追踪时，下一次生成时间会设为“当前时间＋配置间隔”。
- 达到本轮总量后停止追加，等追踪集合清空才进入奖励流程；默认冷却为 **36,000 tick，约 30 分钟**。因此玩家离开后，怪物占满同时上限而无人清理，刷怪也会停住。[生成失败分支](sources/.local/mc-knowledge/spawner-26.3-evidence.md:1067)、[解除追踪与延迟](sources/.local/mc-knowledge/spawner-26.3-evidence.md:1155)、[追踪条件](sources/.local/mc-knowledge/spawner-26.3-evidence.md:1381)

**试炼的“通用默认值”不等于密室内所有配置。** 26.3 原版 JAR 中，普通模式僵尸配置实际为 **20 tick 间隔、同时 `⌊3+0.5(p−1)⌋` 只**；慢速远程骷髅为 **160 tick、同时 `4+2(p−1)` 只**；旋风人为 **20 tick、同时 `⌊1+0.5(p−1)⌋` 只，总量 `2+(p−1)`**。不祥模式也有独立配置。[这三份原版 JSON](sources/.local/mc-knowledge/spawner-26.3-evidence.md:1401)

这些结论来自目标版本数据与静态反编译，**未启动客户端或服务器做运行验证**。试炼还受和平难度、生成相关游戏规则等开关约束；区块停止 tick 后也不会在后台实际生成，重新开始 tick 时才检查已到期的绝对时间。未将其他版本、模组行为或自定义配置混入上述默认值。