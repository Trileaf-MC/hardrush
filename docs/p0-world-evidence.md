# P0 动态世界证据与复测入口

本页只覆盖 Minecraft 26.3 的世界原型。记录日期：2026 年 10 月 13 日。**尚未取得三维度传送、原生首龙及重启恢复的完整实测通过结果，不能据此宣布世界 P0 验收通过。**

## 已有证据

| 层级 | 已核查内容 | 能说明什么 |
| --- | --- | --- |
| 官方类文件及 Fantasy 源码 | 26.3 原版 `ServerLevel` 根据 `DimensionType.hasEnderDragonFight()` 创建本维度的 `EnderDragonFight` SavedData；Fantasy `getOrOpenPersistentLevel` 必须每次启动调用 | 当前实现有明确的版本 API 依据，不依靠大厅的龙战管理器 |
| 注入目标核查 | 下界／末地 `getPortalDestination` 的实际方法描述符；原版构造器从全局 `WorldOptions.seed()` 读取结构检查及龙战种子的路径 | 定点改写本轮维度及本轮种子的依据；源级吻合不等于实际传送正确 |
| 编译 | 世界包已对官方 26.3、Fantasy 0.8.4+26.3、Fabric Mixin 类路径编译通过；统一工程也曾编译通过 | Java API 可解析；出生点修复后的统一构建仍以最新构建结果为准 |
| JUnit | `WorldManifestTest` 报告 9 项通过，0 失败、0 错误、0 跳过，报告时间 `2026-10-13T06:54:43.745Z` | 真实文件往返、完整 long 种子／轮次／生成配置保留、出生点更新、损坏或缺失字段拒绝、文件及父目录符号链接隔离；不覆盖 Minecraft 世界运行 |
| 首次服务端运行 | 服务端启动完成；`world create 12345` 在安全出生点搜索阶段失败；三个持久维度及独立龙 SavedData 文件已出现 | 已执行且失败的集成尝试，不算世界就绪或传送通过 |

JUnit 源码：[WorldManifestTest.java](../platforms/mc263/src/test/java/dev/hardrush/p0/world/WorldManifestTest.java)。报告位置：`platforms/mc263/build/test-results/test/TEST-dev.hardrush.p0.world.WorldManifestTest.xml`。

首次运行失败的原因已由官方字节码确认：未加载区块时 `Level.getHeight(...)` 返回世界最低高度，不负责生成区块。修复改为使用原版生成器的出生区域候选，显式取得生成后的区块，读取区块高度图并逐列检查安全站位。该修复未修改轮次 ID、种子或已有世界文件，等待重启复测。

## 当前清单与恢复检查

当前测试清单为 `.local/server-p0/p0-lobby/hardrush-p0/world-manifest.json`，已登记：

- 轮次 `99898f68-a364-4469-b837-6664da246c39`，种子 `12345`。
- 三个维度前缀 `hardrush:p0_99898f68a3644469b8376664da246c39/`，后缀分别为 `overworld`、`nether`、`end`。
- 初次失败时没有 `spawn` 字段；修复后的恢复流程应补写安全站位，保持轮次、种子和生成配置不变。

实际测试以当前清单为准；以下 `<OW>`、`<NETHER>`、`<END>` 均替换为对应完整维度 ID，`<玩家>` 替换为在线原版测试玩家名字。控制台命令不加 `/`。

先执行 `hardrushp0 status` 确认世界就绪及 `spawn`，再执行 `save-all flush`、`stop`，正常重启后再次读取状态。两个状态中的轮次、种子、出生点及三个 ID 必须相同。重复 `hardrushp0 world create 12345` 应复用本轮；`hardrushp0 world create 54321` 应拒绝，不能改写清单。

本轮龙数据位于 `p0-lobby/dimensions/hardrush/p0_99898f68a3644469b8376664da246c39/end/data/minecraft/ender_dragon_fight.dat`。大厅原生末地也有自己的文件，不能混用证据。未有玩家进入本轮末地时 `dragonUUID=null` 可以是正常状态；文件存在不证明龙已经实际生成或战斗成功。

## 原版门的最小触发步骤

以下步骤会在独立 P0 世界放置测试门，目前未执行。`world enter` 只用于准备测试起点，不能把这条直接传送命令的成功当作原版门路由通过。

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

用主世界当前位置的真实末地门方块触发进入，不直接执行 `world enter ... end` 来替代路由测试：

```text
hardrushp0 world enter <玩家> overworld
execute as <玩家> at @s run setblock ~ ~ ~ minecraft:end_portal
execute as <玩家> at @s if dimension <END> run say P0_END_ENTER_OK
```

等待原版竞技场区块加载及龙战扫描；随后记录：

```text
hardrushp0 status
execute in <END> as @e[type=minecraft:ender_dragon] run data get entity @s UUID
```

必须是原生龙战生成的龙，不能使用 `/summon ender_dragon` 替代这项证据。保存其 UUID，正常保存、停止及重启，再让玩家进入同一本轮末地，核对同一龙战记录且没有重复龙。

返回路由可先在玩家脚下放置测试末地门，再确认去向：

```text
execute as <玩家> at @s run setblock ~ ~ ~ minecraft:end_portal
execute as <玩家> at @s if dimension <OW> run say P0_END_RETURN_OK
```

无有效本轮床／锚时应回清单里的本轮出生点，有有效本轮床／锚则沿用原版重生选择。此测试门只验证返回路由；击败首龙、自然出口门出现、再次召龙及其重启恢复仍需单独实测。

## 当前局限

- 只有一个固定持久 P0 轮次，没有轮换、删除、赛制、保护区、SQLite 状态机或跨轮玩家档案处理。
- 目前只适配 26.3。原版客户端实测、26.1.2／26.2 适配及其他模组组合尚未由本页证明。
- 出生点准备和安全站位查询仍在主线程按有限候选同步执行；P1 应改为分片准备与可恢复任务，并测量首次区块生成延迟，不能把当前原型视为生产性能方案。
- `close` 刷新世界数据，真正关闭由服务端正常停止流程完成；不删除、替换或主动清理任何存档。
- 固定清单与 SavedData 的正常保存路径不等同于崩溃恢复验证。独立时钟、天气、完整原版进度语义及运行中故障恢复仍需后续实现和验收。
