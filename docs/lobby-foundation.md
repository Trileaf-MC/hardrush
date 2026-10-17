# 26.3 第 1 轮大厅交互底座

2026-10-17，第 1 轮实现交接。执行与证据结论以[交接状态](lobby-delivery-status.md)为准。本页描述可复用代码，不表示完整营地、个人追猎、复生祭坛或书屋已经接通。

## 布局与落点

[core/lobby/LobbyLayout](../core/src/main/java/dev/hardrush/core/lobby/LobbyLayout.java) 是唯一坐标契约，版本为 1。所有点均相对长期大厅出生点，X 范围 -32～31，Z 范围 -24～23；中央落点与集结边界相隔 9 格。`Landing` 区分中央、怪物、圈外等候及恢复圈内；`Facility` 定义规则、历史、中途门、三角色、翻页和祭坛锚点；`displaySlots()` 提供 20 个至少相隔 3 格的展示位。人数和候选数量没有因此被限制。

[P1LobbyWorld.layoutPosition(Point) / landing(Landing)](../platforms/mc263/src/main/java/dev/hardrush/p1/world/P1LobbyWorld.java) 将坐标契约转换为本大厅绝对位置。**它们不保证该位置的区块或安全性**，也不执行传送。当前出生区仍只预载 3 × 3 区块。第 2 轮必须按布局覆盖范围加载、检查站位、执行有归属和版本的幂等建设，再开放对应区域与设施；不能把此布局常量当成已经建好的营地。RESTORE_READY 的安全检查与恢复准备语义由第 2 轮实现。

## 设施与两层网络保护

[LobbyInteractions](../platforms/mc263/src/main/java/dev/hardrush/p1/lobby/LobbyInteractions.java) 在 `P2LobbyRuntime.configure` 时配置。所有调用在服务端主线程完成。

```java
var router = LobbyInteractions.get(server).orElseThrow();
router.registerBlock(ruleLectern, Blocks.LECTERN, LobbyContext.RULES,
        player -> P2LobbyUi.open(player, LobbyContext.RULES));
router.registerBlock(nextSign, Blocks.OAK_SIGN, LobbyContext.DISPLAY,
        player -> router.projections().turn(player, 1));
```

`registerBlock` 仅接受专用讲台和橡木翻页牌，拒绝箱子等普通容器。登记绑定当前轮次与预期方块，实际方块被替换后请求失效。登记不会放置方块或绕过世界保护。第 1 轮只有隔离测试预置最小讲台／牌；正式营地设施由第 2 轮建设后登记。未来新增展位类型应显式扩展登记类型，不能全局放开容器或实体回调。

`ProtectionNetworkMixin` 与 `PlayerIsolationNetworkMixin` 都调用 `block` / `entity`，任何已登记请求（成功或拒绝）都会被消费并取消原版交互。两个保护层顺序交换也使用同一权威路径；同一连接的同一包对象只执行一次回调。缓存只保留最近一个包，网络上另一个同内容包仍会重新验证；它不能代替业务请求的去重／事务门槛。方块输入确认 sequence，并发送目标与邻接方块校正，防止客户端预测留下假方块；只读取已加载区块。

授权依次检查轮次、当前 `ServerPlayer` 与连接对象、连接存活、玩家存活、本大厅、上下文服务能力、5 格服务端距离、主手及命中数据。不同观察者的人偶 ID 不互通。未知实体与未登记方块走原有保护，攻击／装备／普通容器保护仍然有效；未核验、死亡处理中、档案故障或过期连接不会获得设施权限。`ACCEPTED` 只表示设施回调已受理，不能当作参赛、追猎、复生或数据库提交成功。

| `Result` | 含义与处理 |
| --- | --- |
| ACCEPTED | 专用回调已执行一次；回调负责业务重验及持久化 |
| UNREGISTERED | 当前观察者无此设施／实体映射，继续原有保护，不能转成通用交互许可 |
| STALE | 轮次／登记方块失效，重新登记或刷新 |
| IDENTITY | 过期连接、死亡或不在有效大厅 |
| DISTANCE | 不在服务端 5 格范围 |
| INPUT | 副手、异常命中或不允许的交互形式 |
| UNAVAILABLE | 身份或对应服务尚未开放，包括暂离／死亡／关闭的人偶 |

`lastResult(player)` 可供服务器诊断；它不发放权限。登记与授权前都同步轮次，避免新建服务尚未执行首次 tick 时把刚登记的设施误清掉。

## 菜单上下文与提示仲裁

[LobbyContext](../core/src/main/java/dev/hardrush/core/lobby/LobbyContext.java) 提供 RULES、HISTORY、DISPLAY、COMPETITION。[P2LobbyUi.open(player, context)](../platforms/mc263/src/main/java/dev/hardrush/p2/lobby/P2LobbyUi.java) 将上下文和轮次固定到该菜单，`Access` 按上下文分别提供可用性、按钮与动作。旧 `open(player)` 仍表示竞技菜单。规则菜单没有加入、投票、复生等竞技动作，默认只读动作不会调用竞技处理器。

规则入口要求本连接已完成档案核验并具有安全大厅身体；世界准备期间可以读取。FAILED／存储重核中服务不开放；存储重核成功后，即使轮次仍是 RECOVERY_REQUIRED，已核验大厅身体也可读规则。真正未核验或不确定的玩家继续隔离，新登录者不会因打开规则而自动完成档案恢复。HISTORY 当前明确关闭，待第 4 轮实际查询服务可用后接入，不伪造空历史。隔离身体跳过原版 tick 时，交互服务仍重验和关闭失效菜单。

[LobbyHints](../core/src/main/java/dev/hardrush/core/lobby/LobbyHints.java) 按 `RECOVERY > PROGRESS > READY > TARGET > FACILITY` 仲裁，来源有独立租约与撤销接口，同级按来源键稳定选择。`router.hints(player)` 提供按连接隔离的实例；`offer(source, priority, text, expires)` 和 `current(now)` 使用同一单调毫秒时钟（建议 `System.nanoTime()/1_000_000`）。重连和轮换清理实例。第 4 轮把各进度／设施来源汇入此仲裁后统一写行动栏，当前没有将所有旧聊天、TAB 或音效改造到该服务。

## 全身人偶投影

[LobbyProjections](../platforms/mc263/src/main/java/dev/hardrush/p1/lobby/LobbyProjections.java) 是发布代码内可复用的 26.3 实现，通过 `router.projections()` 使用。

- `show(observer, round, targets, page, callback)`：按零起始页码建立该连接的整页展示，返回含 generation、页码、页数和 `Selection` 的 `View`。`Target` 接收原始 `GameProfile`、生命序号和 AVAILABLE／AWAY／DEAD／CLOSED 状态。UUID 不可重复，生命序号须为正。无效身份、轮次、页码或目标参数会抛异常且不应被当作业务成功。
- `turn(observer, ±1)`：使用该观察者原候选快照与回调翻页；无页面或越界返回空。必须由已登记翻页牌调用。候选状态改变时服务端调用 `show` 刷新相关观察者；第 3 轮负责本轮 UUID 稳定槽位、死亡留空、暂离及复生的数据来源，不能排序删除而挪用旧展位。
- `view(observer)`：只返回本连接映射。`Selection` 明确携带轮次、目标 UUID、生命序号、generation、实体 ID；从不按显示名字授权。
- `remove(observer)` / `clear()`：先删除权威映射，再向原连接发送 RemoveEntities。原生断线、替换连接、停服、换轮、不再具有 DISPLAY 权限或恢复隔离会失效和清理页面。

每个模型是真正的原生 `Mannequin` 类型，其 profile 通过 `DataComponents.PROFILE` 与 `ResolvableProfile.createResolved` 写入同步元数据。应传 `ServerPlayer.getGameProfile()` 等原资料，不能从 `OwnerSkin` 改写过的 PlayerInfo 包取得 Steve 外观。无 textures 时由客户端使用默认皮肤，名字仍可识别。实现不请求或缓存外部皮肤下载，不同步真实位置、朝向、装备、背包或血量；人偶位置和朝向只取大厅契约，装备为空。

人偶仅向该观察者发送 AddEntity + SetEntityData，不加入服务端世界、原生 tracker 或玩家列表，不参与 TAB、人类资格或末地统计。投影与怪物伪装不共享生命周期。

**旧点击安全性不依赖客户端携带 generation。** 原生右键包只有实体 ID 等输入；每次 show 都销毁旧映射并新建模型，使用 Minecraft 全局递增分配器，不调用 setId，不把旧 ID 绑定给新目标。不同观察者也使用不同 ID。整数分配耗尽或回绕时失败关闭，须重启，不能回收旧 ID。原进程连接不跨 JVM 存活。整个页面先构造完成、发送后再开放映射；服务端线程中没有半页换绑窗口。

DISPLAY 是展示／选取基础能力，尚未授予追猎权。回调仍须由第 3 轮按当前怪物身份、目标生命、阶段、冷却、轮次与提交状态重新验证，并把它接到个人请求。当前没有注册正式追猎回调，也没有冷却归零自动出场。

## 规则迁移与旧轮安全门槛

[RulesConfig](../core/src/main/java/dev/hardrush/core/config/RulesConfig.java) 的 schema 1 固定表示 LEGACY_VOTE，schema 2 表示 PERSONAL_TARGET。没有在旧编码中补键；schema 1 的原 UTF-8 编码和指纹保持不变。测试的旧默认固定指纹为 `1b6d9b7877a9aba787c0929a912e59b86a56571fba739f0c44c264b8f7d09640`，golden 文件由基线源码提取。schema 2 形成不同分榜指纹。

`defaults()` 用于新轮，返回 schema 2。`forNewRound()` 只转换待用规则，不修改活动快照。Session 打开数据库时持久化升级待用规则；加载的活动／历史规则仍逐字保留。`RoundSnapshotCodec` 外层仍为 5，继续读取 1～5；兼容的 `monsterDimension` 字段没有删除。`withMode` 保留当前 schema，不追改旧榜与旧书。

`RoundEngine.requiresLegacyClosure(snapshot)` 对没有终局结果的旧规则活动轮返回 true。`restore` 即使看到 cleanShutdown，也将它留在 RECOVERY_REQUIRED，保留规则、种子、轮次、计时余量、身份和资格；Session 不建立旧倒计时恢复放行。适配器就绪或原准备 UUID 上线均不能续赛。`P2LobbyRuntime.synchronize` 在恢复隔离和新规则下不创建或刷新投票服务；新规则菜单和请求同时关闭 vote／dimension／deploy。

升级前可在旧构建完成旧活动轮。已经加载到新构建时，按既有管理员恢复流程核验并审计 `close-recovery`，不自动中止；新轮随后使用待用的 schema 2。已结算且正常保存的旧终局按原结算／庆祝／轮换流程结束，旧指纹不变；脏退出仍遵守原隔离门槛。回退时使用匹配旧构建的一致性备份，旧构建不承诺读取 schema 2。

## 自动验证与人工交接

从仓库根执行：

```sh
scripts/p0-gradle.sh checkP3 -PhardrushP1=true --console=plain
scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyFoundation=true --console=plain
scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyMovement=true --console=plain
```

`checkP3` 任务通过 checkP2／checkP1 依赖 core、persistence、26.3 check 和 jar；Loom check 包括默认无图形 GameTest。默认套件中未启用的专项会占位成功，因此总数量不代替专项实跑。新增 `hardrushLobbyFoundation` 显式过滤 `lobby_foundation_game_test*`，使用 `.local/lobby-foundation/<UUID>`，并拒绝与其他改写共享大厅的专场组合。默认 check 使用 `.local/baseline-check/<UUID>`。均只复制用户已接受的 EULA，不操作日常存档。

协议专场使用两个原生 PrepareSpawnTask、ServerGamePacketListenerImpl、EmbeddedChannel、实际输入包和输出包，无图形客户端。入站右键和出站人偶 profile 元数据使用原生 wire codec。21 个展示目标中包括模拟原皮肤属性与构造候选，只有两名真实服务端连接；它不证明 21 人负载。讲台、箱子与翻页牌是夹具在短暂的预置作用域内放置，**所有被验证的输入都在生产保护恢复后执行**。夹具只对测试服务端注入时钟，受控存储故障和审计结束旧轮用于清理验证；不计为真实故障介质验收。

JUnit 包括布局间隔／落点用途、提示优先级／过期、schema 1 golden／codec、旧活动阶段安全门槛，以及真实 SQLite Session 的旧 COUNTDOWN、原 UUID、拒绝就绪、保留数据库、审计关闭与重启。源码标识、实际命令、各次成功和失败日志见[证据目录](evidence/lobby-foundation-20261017/)和[状态](lobby-delivery-status.md)。旧 P2 投票／共同出场专场保留，未作为新规则通过证据；第 3 轮迁移其直接依赖投票的业务夹具。

人偶皮肤渲染、默认皮肤降级、准星选取、模型文字、营地布局与完整游玩继续按[现有人工清单](人工客户端测试/大厅无指令交互与定向追猎.md)手测，全部保持未执行。本轮未启动浏览器、computer use 或图形／真实游戏客户端。

第 2 轮起点：先复核本轮源码与证据，再实现布局覆盖加载、幂等营地建设与设施登记、落点安全、权威入圈准备、中途门及正常倒计时恢复。第 3 轮复用投影和规则边界接通个人追猎；第 4 轮接通只读历史查询和各提示来源。第 1 轮不自动执行这些工作。
