# P0 动态世界证据与复测入口

本页只覆盖 Minecraft 26.3 的世界原型。记录日期：2026 年 10 月 13 日。主要世界路径已有实测证据，下面区分纯原版客户端和 MCPFabric 调试客户端；完整 P0 仍因自身外观 C03 失败而未通过，详见 [验证总表](p0-validation.md)。

同日新增的 P0-15 边界回归、进度／时钟／天气修复和正常停服重启证据见 [世界边界记录](p0-world-boundaries.md)。下面保留早期运行快照，不能用新增服务端测试替代原版客户端末地完整流程。

P0-14 于 2026-10-14 按低血量夹具范围补齐原版真实走门、同轮首龙重启、死亡重生、普通最后一击及实际走自然出口返回；龙战击杀标志正常保存，服务端死亡／出口／复召回归两次通过，见 [原版末地流程进展](p0-vanilla-end-evidence.md)。下方早期结果保留原样，完整满血生存战斗未作为通过结论。

## 已有证据

| 层级 | 已核查内容 | 能说明什么 |
| --- | --- | --- |
| 官方类文件及 Fantasy 源码 | 26.3 原版 `ServerLevel` 根据 `DimensionType.hasEnderDragonFight()` 创建本维度的 `EnderDragonFight` SavedData；Fantasy `getOrOpenPersistentLevel` 必须每次启动调用 | 当前实现有明确的版本 API 依据，不依靠大厅的龙战管理器 |
| 注入目标核查 | 下界／末地 `getPortalDestination` 的实际方法描述符；原版构造器从全局 `WorldOptions.seed()` 读取结构检查及龙战种子的路径 | 定点改写本轮维度及本轮种子的依据；源级吻合不等于实际传送正确 |
| 编译与自动回归 | 出生点修复后的统一 `checkP0` 构建通过；9 项 JUnit、3 项 HardRush 服务端 GameTest 和 1 项框架测试通过 | 原型可构建、已覆盖的自动回归通过；不能替代未执行的世界场景 |
| JUnit | `WorldManifestTest` 报告 9 项通过，0 失败、0 错误、0 跳过，报告时间 `2026-10-13T06:54:43.745Z` | 真实文件往返、完整 long 种子／轮次／生成配置保留、出生点更新、损坏或缺失字段拒绝、文件及父目录符号链接隔离；不覆盖 Minecraft 世界运行 |
| 首次服务端运行 | 服务端启动完成；`world create 12345` 在安全出生点搜索阶段失败；三个持久维度及独立龙 SavedData 文件已出现 | 已执行且失败的集成尝试，不算世界就绪或传送通过 |
| 出生点修复复测 | 正常重启保留原轮次及 seed=12345，并补写 spawn=(96,132,-23)；同种子重复 create 保持身份，异种子拒绝 | 修复后的统一构建与运行恢复通过，未覆盖坏清单 |
| 纯原版客户端 | HRProbeA 实际触发下界门双向传送；管理员进入本轮末地后显示原生龙血条 | 下界往返与原生首龙显示有原版证据；管理员传送不算末地门进入 |
| 调试客户端实测 | HRDebug 走入末地门，在轮次 `8e7ef667-ad98-4507-97fc-439847d19460` 从主世界进入同轮末地，落点 `(100.5,49,0.5)` | 原版末地门调用链在调试环境中实际触发，不是纯原版客户端证据 |
| 龙战及重启 | 首龙 UUID `1b5fc10e-660c-4e53-81e3-dce1fa8e4219` 正常重启后保持；仅 1 个存活主体。随后在增强攻击的夹具条件下由客户端发出攻击，触发原版死亡流程与出口门 | 验证龙战数据、死亡及出口路径，不是普通生存击杀或 HardRush 尾刀计分验收 |
| 自然出口返回 | HRDebug 落入自然生成的末地出口，回到本轮主世界 `(96.5,132,-22.5)` | 未手动放置出口门，也未通过 world enter 代替返回 |

JUnit 源码：[WorldManifestTest.java](../platforms/mc263/src/test/java/dev/hardrush/p0/world/WorldManifestTest.java)。报告位置：`platforms/mc263/build/test-results/test/TEST-dev.hardrush.p0.world.WorldManifestTest.xml`。

调试客户端末地进入记录：`.local/p0-evidence/debug-end-portal-entry.json`。其中的测试轮次与下述早期 `server-p0` 清单不同，不能将两份记录当成同一轮的连续恢复证据。

首次运行失败的原因已由官方字节码确认：未加载区块时 `Level.getHeight(...)` 返回世界最低高度，不负责生成区块。修复改为使用原版生成器的出生区域候选，显式取得生成后的区块，读取区块高度图并逐列检查安全站位。重启复测已通过，轮次 ID 和种子保持不变。

## 当前清单与恢复检查

当前测试清单为 `.local/server-p0/p0-lobby/hardrush-p0/world-manifest.json`，已登记：

- 轮次 `99898f68-a364-4469-b837-6664da246c39`，种子 `12345`。
- 三个维度前缀 `hardrush:p0_99898f68a3644469b8376664da246c39/`，后缀分别为 `overworld`、`nether`、`end`。
- 初次失败时没有 `spawn` 字段；修复后的恢复流程已补写安全站位 `(96,132,-23)`，轮次、种子和生成配置不变。

实际测试以当前清单为准；以下 `<OW>`、`<NETHER>`、`<END>` 均替换为对应完整维度 ID，`<玩家>` 替换为在线原版测试玩家名字。控制台命令不加 `/`。

先执行 `hardrushp0 status` 确认世界就绪及 `spawn`，再执行 `save-all flush`、`stop`，正常重启后再次读取状态。两个状态中的轮次、种子、出生点及三个 ID 必须相同。重复 `hardrushp0 world create 12345` 应复用本轮；`hardrushp0 world create 54321` 应拒绝，不能改写清单。

本轮龙数据位于 `p0-lobby/dimensions/hardrush/p0_99898f68a3644469b8376664da246c39/end/data/minecraft/ender_dragon_fight.dat`。大厅原生末地也有自己的文件，不能混用证据。未有玩家进入本轮末地时 `dragonUUID=null` 可以是正常状态；文件存在不证明龙已经实际生成或战斗成功。

## 原版门的最小触发步骤

以下是独立 P0 世界的复测步骤，会放置测试门；实际已取得的证据见上表。`world enter` 只用于准备测试起点，不能把这条直接传送命令的成功当作原版门路由通过。

每次跨维度之后，玩家先离开所有传送门，至少等待 10 个实际游戏刻，再重新走入。26.3 的 `Player.getDimensionChangingDelay()` 返回 `10`，正常 20 TPS 下约为 **0.5 秒**；操作时可留出 1 秒余量。冷却期间继续接触任何门，`Entity.setAsInsidePortal()` 都会将冷却重新设为 10，因此一直站在门内并不能等完冷却。`ServerPlayer.isChangingDimension` 尚未解除时，冷却也不会递减。

### 主世界到下界与返回

先让测试玩家进入本轮主世界并保持站立不动，再用控制台在其前方放置标准 4×5 门框及门方块：

```text
hardrushp0 world enter <玩家> overworld
data get entity <玩家> Pos
execute as <玩家> at @s run fill ~8 ~ ~ ~11 ~4 ~ minecraft:obsidian
execute as <玩家> at @s run fill ~9 ~1 ~ ~10 ~3 ~ minecraft:nether_portal[axis=x]
execute as <玩家> at @s run tp @s ~9 ~1 ~
```

让原版传送等待时间实际结束，再检查：

```text
execute as <玩家> at @s if dimension <NETHER> run say P0_NETHER_ENTER_OK
data get entity <玩家> Pos
```

记录入门前后坐标。目标搜索锚点的 X/Z 应按主世界坐标除以 8 计算，实际落点允许受原版找门／建门影响，不能要求逐坐标完全相等。随后玩家离开下界门，等待冷却，再走回同一扇门；通过 `if dimension <OW>` 确认返回本轮主世界，不能回大厅或其他轮次。

### 末地门、原生首龙与返回

先到主世界的安全地面，离开所有门至少 2 格并等待上述冷却，再在相邻位置放置真实末地门。不直接执行 `world enter ... end` 来替代路由测试：

```text
hardrushp0 world enter <玩家> overworld
execute as <玩家> at @s run setblock ~3 ~ ~ minecraft:end_portal
```

玩家从相邻方块实际向东走入门方块，让身体与门的触发区域相交，再执行检查。仅在脚下换方块或用 `/tp` 改坐标不算完成这一步：

```text
execute as <玩家> at @s if dimension <END> run say P0_END_ENTER_OK
```

早期测试在下界返回后持续留在门内，随后将脚下换成末地门而没有触发传送。持续接触刷新冷却可以解释这一现象，但当时未捕获冷却值，不能认定具体原因，更不能据此认定末地路由有缺陷。后续 `HRDebug` 通过 MCPFabric 的真实 `forward` 移动已触发同轮末地进入，未为此修改路由代码；纯原版客户端仍需按上述步骤单独记录结果。

等待原版竞技场区块加载及龙战扫描；随后记录：

```text
hardrushp0 status
execute in <END> as @e[type=minecraft:ender_dragon] run data get entity @s UUID
```

必须是原生龙战生成的龙，不能使用 `/summon ender_dragon` 替代这项证据。保存其 UUID，正常保存、停止及重启，再让玩家进入同一本轮末地，核对同一龙战记录且没有重复龙。

返回路由同样需要先离开所有门并等完冷却，在安全地面上的相邻位置放置测试末地门：

```text
execute as <玩家> at @s run setblock ~3 ~ ~ minecraft:end_portal
```

玩家实际走入后，再确认去向：

```text
execute as <玩家> at @s if dimension <OW> run say P0_END_RETURN_OK
```

无有效本轮床／锚时应回清单里的本轮出生点，有有效本轮床／锚则沿用原版重生选择。此测试门只验证返回路由；本次另完成了增强攻击夹具下的首龙死亡、自然出口生成和自然出口返回，证据见总表。再次召龙、常规生存战斗和完整床／锚组合仍未实测。

## 当前局限

- 只有一个固定持久 P0 轮次，没有轮换、删除、赛制、保护区、SQLite 状态机或跨轮玩家档案处理。
- 目前只适配 26.3。纯原版客户端已有下界往返和原生龙显示证据，但末地门进入、完整龙战与返回仍需补齐原版验收；26.1.2／26.2 适配及其他模组组合尚未验证。
- 出生点准备和安全站位查询仍在主线程按有限候选同步执行；P1 应改为分片准备与可恢复任务，并测量首次区块生成延迟，不能把当前原型视为生产性能方案。
- `close` 刷新世界数据，真正关闭由服务端正常停止流程完成；不删除、替换或主动清理任何存档。
- 固定清单与 SavedData 的正常保存路径不等同于崩溃恢复验证。P0-15 已补齐持久昼夜、天气隔离及受控原版维度进度映射的服务端回归与正常重启；运行中故障恢复、跨轮进度隔离及完整客户端验收仍待后续任务。
