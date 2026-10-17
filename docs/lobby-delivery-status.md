# HardRush 26.3 无指令大厅交接状态

初始化日期：2026-10-16。当前阶段：第 1～2 轮开发与自动验证完成，第 3～5 轮未开始。对应[五轮计划](lobby-delivery-plan.md)、[产品设计](lobby-interaction-design.md)和[启动词索引](lobby-delivery-prompts/README.md)。

## 总体状态

| 项目 | 当前状态 |
| --- | --- |
| 交付范围 | 26.3；26.1.2／26.2 正式适配另排 |
| 当前开发轮次 | 第 2 轮开发与自动验证完成；停在本轮，第 3 轮须另行启动 |
| 新交互开发 | 底座、营地、集结开赛、中途加入及正常恢复已交付；角色／庭院业务、个人追猎及外围闭环待后轮 |
| 本计划的自动产品验证 | 最终源码 361 项 JUnit、107 项默认 GameTest；人类入场、底座协议、移动各 1/1，COUNTDOWN 三 JVM 通过；本轮另执行十轮 2/2（范围见下）；占位不代替专项 |
| 真实客户端人工验收 | 未执行，待实现后按人工清单开展 |
| Git 提交 推送 部署 | 第 1～2 轮已按用户要求提交，提交说明见文末。未推送或部署；两轮各自验证基线保持原记录 |
| 文档准备检查 | 11 份 Markdown 的结构与 171 个本地链接检查通过；5 份独立启动词和 5 轮未开始状态校验通过，只读复审通过 |

## 初始化基线

- 仓库：/Users/leifwebber/projects/minecraft/hardrush。
- 2026-10-16 初始化时分支为 main，HEAD 为 8c7e911c42c6721982777e62559c93e2049a8eb3。该 HEAD 不代表整个工作树，也不是本计划的开发完成版本。
- 工作树存在先前核心、档案、庆祝、测试脚本和文档改动，以及未跟踪文件。不得 reset、清理、覆盖或把它们全部归入新大厅工作。
- 第 1 轮开始时重新读取实际 Git 状态与相关文件，不假设初始化基线仍未变化。逐轮记录自己修改的文件和源码摘要。
- 当前只创建文档；没有启动 Gradle、GameTest、游戏服务端、真实客户端或浏览器测试，没有修改产品代码、存档或数据库。

## 五轮状态表

| 轮次 | 状态 | 前置门槛 | 本轮证据 | 下一步 |
| --- | --- | --- | --- | --- |
| 1 交互底座与关键风险 | 开发与自动验证完成 | 已核对干净基线并实跑当前门槛 | [验证汇总](evidence/lobby-foundation-20261017/validation.json)／[接口说明](lobby-foundation.md) | 停止本轮；第 2 轮另行启动并先复核 |
| 2 营地与人类入场 | 开发与自动验证完成 | 前轮 24 个源码及 3 份成功日志摘要、接口已复核 | [基线](evidence/lobby-human-entry-20261017/baseline.json)／[验证汇总](evidence/lobby-human-entry-20261017/validation.json) | 停止本轮；第 3 轮另行启动并先复核 |
| 3 皮肤庭院与定向追猎 | 未开始 | 第 1～2 轮自动门槛已通过，待第 3 轮会话复核 | 无 | 按第 3 轮启动词接角色／庭院与请求 |
| 4 全流程无指令接通 | 未开始 | 第 1～2 轮已通过，第 3 轮未执行 | 无 | 等待第 3 轮通过并交接 |
| 5 稳定化与候选包 | 未开始 | 第 1～2 轮已通过，第 3～4 轮未执行 | 无 | 等待全部玩家业务实现 |

后轮未开始不等于技术失败。第 1～2 轮执行发现的失败与修复见下文；两轮必需自动门槛已通过，人工画面与后轮业务仍未验收。

## 第 1 轮交接

- 状态：开发与自动验证完成。限 26.3，第 2～5 轮未执行；人工验收未执行。
- 执行会话及日期：01a147b0-30da-71d0-bf3e-1fdb595ba0f2，2026-10-17。
- 启动基线：main，HEAD `3fc1f835a5753fca63f9c6c98f34aac191f182d5`，工作树干净。适用 AGENTS.md 仅仓库根文件；没有清理、重置或覆盖先前成果。原源码及涉及文档 SHA-256 见 [baseline.json](evidence/lobby-foundation-20261017/baseline.json)。
- 验证时源码：当时未提交的本轮工作树；24 个新增／修改源码、测试及构建文件逐项列于 [source-files.json](evidence/lobby-foundation-20261017/source-files.json)，聚合 SHA-256 为 `5e9a07f1f3f514c32bfd9e9520bf405f8238c698d758de911dd56d3cc68d598e`。执行上述验证时 HEAD 未改变；随后按用户要求提交，见文末。不能把验证基线 HEAD 单独当成本轮实现标识。
- 实际改动：新增 core/lobby 的布局、上下文与提示仲裁；新增 26.3 的 LobbyInteractions、LobbyProjections；将两层网络保护接到同一设施路由；扩展 P1LobbyWorld 坐标接口、P1PlayerProfiles 只读身份门槛、P2LobbyUi 上下文和恢复菜单清理；RulesConfig schema 2、新轮待用规则迁移、RoundEngine／Session 旧活动轮隔离；新增 JUnit／原生双连接探针及独立运行入口。原 P2Identity 掉落夹具补实体区块就绪等待，保留原断言。所有源码归属和调用约定见[接口说明](lobby-foundation.md)。
- 布局／设施／落点接口：`LobbyLayout.VERSION=1`、`Landing`、`Facility`、`displaySlots()`；`P1LobbyWorld.layoutPosition/landing`；`LobbyInteractions.registerBlock`。坐标不是已加载或已建造保证，营地覆盖加载、幂等建设和安全传送由第 2 轮实现。
- 人偶投影／页面映射：`router.projections().show/turn/view/remove/clear`，原生全身 Mannequin 使用原 GameProfile。映射绑定观察者对象、连接、轮次、generation、实体 ID、目标 UUID 和生命序号；每次展示使用原生新 ID，旧 ID 永不换绑。断线、替换连接、恢复、轮换和停服清理，与怪物伪装独立。DISPLAY 只交付展示与选取基础，第 3 轮仍须重验怪物／目标并接个人追猎业务。
- 新旧规则边界：schema 1=LEGACY_VOTE，schema 2=PERSONAL_TARGET；旧规则编码与固定指纹 `1b6d9b7877a9aba787c0929a912e59b86a56571fba739f0c44c264b8f7d09640` 保持稳定。外层 RoundSnapshotCodec 仍为 5，兼容字段保留。旧活动轮即使 cleanShutdown 也进入 RECOVERY_REQUIRED，不续旧倒计时、不新建投票；在旧构建结束，或按既有审计恢复流程结束旧轮。只升级待用／新轮规则。新规则 vote／dimension／deploy 菜单与请求均关闭，个人追猎未接通。
- 只读菜单与提示：`P2LobbyUi.open(player, LobbyContext.RULES)` 可供已核验大厅身体在准备及成功重核后的恢复期阅读；真实隔离、FAILED／重核中不放行。HISTORY 当前关闭，等待第 4 轮实际查询服务；COMPETITION 按世界、阶段与身份独立开放。`router.hints(player)` 提供按连接隔离的 `LobbyHints`，恢复 > 进度 > 准备 > 目标 > 设施；第 4 轮统一接提示来源与渲染。

### 最终自动验证

均从 `/Users/leifwebber/projects/minecraft/hardrush` 执行，使用新目录，没有操作日常存档。汇总含准确命令、日志摘要、实际规则指纹和轮次：[validation.json](evidence/lobby-foundation-20261017/validation.json)。

| 命令 | 当前结果 | 运行目录与证据 |
| --- | --- | --- |
| `scripts/p0-gradle.sh checkP3 -PhardrushP1=true --console=plain` | 通过；354 项 JUnit（core 152、persistence 94、26.3 adapter 108），106 项默认无图形 GameTest | `.local/baseline-check/21e53980-c000-464c-adba-a2cd84317a32`；[checkP3-complete.log](evidence/lobby-foundation-20261017/checkP3-complete.log)、[JUnit 汇总](evidence/lobby-foundation-20261017/junit-summary.json) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyFoundation=true --console=plain` | 独立协议专场 1/1 通过；真实生产保护与两个原生嵌入连接 | `.local/lobby-foundation/8e3f661a-7eb4-493e-aeee-b5e83a71bc4d`；[protocol-complete.log](evidence/lobby-foundation-20261017/protocol-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyMovement=true --console=plain` | 受影响大厅移动／重连／恢复／轮换专场 1/1 通过 | `.local/lobby-movement/455f12ec-9f3e-4d25-93ce-8faf2eec0b42`；[movement.log](evidence/lobby-foundation-20261017/movement.log) |

默认套件有未启用专项的占位成功，不因此声称本轮协议、旧投票／定向追猎业务或所有原生场景已经通过。新增 4 项 core JUnit 与 1 项真实 SQLite Session 用例结果另存为 [core XML](evidence/lobby-foundation-20261017/TEST-dev.hardrush.core.lobby.LobbyFoundationTest.xml)、[Session XML](evidence/lobby-foundation-20261017/TEST-dev.hardrush.p1.LobbyRuleUpgradeTest.xml)。

协议专场证明：登记设施实际可达；两层保护共用幂等路由；隔离、未登记实体、普通容器、远程及异常命中拒绝；原生 Mannequin 与原 profile 出站 wire codec；21 个展示目标下双观察者独立翻页；跨页、换生命、跨观察者、重连、恢复及轮换后的旧实体包无法点中新目标；无真实实体／玩家计数污染。21 个目标是构造候选，只有两个模拟连接。夹具短暂预置讲台／箱子／牌后恢复生产保护，再输入实际包；测试时钟、受控故障和审计结束只用于验证，不声称真实介质故障或完整生存游玩通过。

构建制品 `platforms/mc263/build/libs/hardrush-0.1.0-p0+mc26.3.jar` 的 SHA-256 为 `8bd4ab455fbfe8f9878671af297ef2d0e3d5b0fe2609eebf29fa406dd072a96b`；检查含本轮实现、声明仅 26.3，未包含 GameTest 或 MCPFabric 类。开发交接时没有发布候选包、部署、提交或推送；后续仅按用户要求提交，见文末。

### 执行过程及失败修复（历史保留）

1. 修改产品代码前先将总表与本轮标为进行中，保存基线；未继承旧批次通过数量。
2. `checkP3-first.log` 发现 26.3 原生 API 名称差异，按当前 JAR 修复；`checkP3-second.log` 发现布局测试错误地比较每次新建列表的对象引用，改为按坐标值排除同一位置，保留距离断言。
3. `protocol-first.log`～`protocol-third.log` 保留测试失败定位及登记时序排查：统一在登记／授权前同步轮次；生产世界保护原本阻止夹具直接施工，改用短暂预置作用域，输入验证仍用生产保护。
4. `protocol-fourth.log` 的恢复等待条件错误：FAILED 不提前改持久化阶段，重核后才进入 RECOVERY_REQUIRED，已按实际生命周期修正；`protocol-fifth.log` 的重复预置把原版相同方块返回 false 误判为失败，改检查实际方块状态。`protocol-sixth.log` 中间专场通过，之后补出站 codec、预测校正、菜单失效与最终关闭清理，再由 `protocol-complete.log` 验证最终源码。
5. `checkP3-third.log` 中间基础门槛通过；`checkP3-final.log` 新 Session 夹具编译时误用提交结果枚举，修为实际 APPLIED；`junit.log` 的 `/var` 符号链接路径被原安全检查拒绝，改夹具 `toRealPath()`，`legacy-session-second.log` 专项通过。最终完整 JUnit 结果以上表为准。
6. `checkP3-verified.log` 发现既有 P2Identity 掉落夹具没有等待实体区块就绪而读到 0 个掉落；补 `areEntitiesActuallyLoadedAndTicking` 等待，未删除或放宽“恰好 3 个掉落／不复制”断言。`checkP3-complete.log` 最终基础门槛全部通过。所有原始失败日志与 GameTest 隔离目录保留；JUnit 的系统 @TempDir 按测试框架生命周期清理。

### 人工与下一轮

[既有人工清单](人工客户端测试/大厅无指令交互与定向追猎.md) 已新增第 1 轮交接项目，全部未执行；无浏览器、computer use 或图形／真实游戏客户端测试。皮肤画面、下载失败降级、准星选取、标签、假方块和完整营地体验仍由人类后续验收，不要求本轮截图。

第 2 轮先复核本轮的 `source-files.json`、原生协议证据、`LobbyLayout`、`P1LobbyWorld` 坐标接口与 `LobbyInteractions`，再按[第 2 轮启动词](lobby-delivery-prompts/02-human-entry.md)实现布局覆盖加载、幂等施工、设施登记、安全落点、权威入圈准备、中途门和正常停服恢复。没有开始第 2 轮，也没有提前实现个人追猎、祭坛、书屋或管理讲台。

## 第 2 轮交接

- 状态：开发与自动验证完成。限 26.3，仅本轮营地与人类路径；第 3～5 轮未执行，人工客户端未验收。随后按用户要求提交，见文末；未推送或部署。
- 执行会话及日期：01a147d1-b4f9-7313-b93d-29e636cee5f6，2026-10-17。
- 基线：main，HEAD `d004e84549e93e804e2b3eeb83765ccb7033dc7d`，启动工作树干净。适用 AGENTS.md 仅仓库根文件；没有 reset、清理、重建状态文件或使用先前聊天历史。完整基线见 [baseline.json](evidence/lobby-human-entry-20261017/baseline.json)。
- 前轮复核：前轮 source-files.json 的 24 个源码／测试／构建文件逐项一致；三份最终成功日志 SHA-256 一致；复读布局、设施双保护统一路由、人偶原生双连接协议及规则边界实现，确认主能力已经交付。前轮没有被本轮替代重做，也没有需要归属第 1 轮的产品缺陷修补。
- 最终源码：本轮 24 个新增／修改的源码、测试、构建与脚本文件列于 [source-files.json](evidence/lobby-human-entry-20261017/source-files.json)，聚合 SHA-256 `e6f06e40fafa36cec484ea2dda843605398c9add95183ded99beafc5377343ca`。验证时 HEAD 未改变，源码为当时未提交工作树；随后按用户要求提交，见文末。不能把基线 HEAD 当作本轮实现版本。

### 营地、设施与落点

`P1CampBlueprint.blocks(anchor)` 是实际施工集合；`P1LobbyWorld` 覆盖相对 X -32～31、Z -24～23 的 64×48 营地，当前锚点下实际持有 15 个区块，最多 4 个未完成原生异步加载，主线程每 tick 最多处理 128 个施工条目。所有实际设施／标牌／屋顶均在该覆盖范围内；加载失败、施工失败或必要落点不安全不开放入口。没有修改 `P1RoundWorldBundle` 的自然地形施工规则，也没有为正式挑战世界增加石台、保护圈或通道。

布局版本仍为 `LobbyLayout.VERSION=1`。存档根下 `hardrush/lobby-layout.txt` 绑定维度、版本、锚点和 PREPARED／COMPLETE 状态；先核对完整行走体积和施工集合，仅认领原始平地，完成 PREPARED 原子落盘后才施工。PREPARED 只能复用同一计划的既有方块；未知方块／未知版本失败关闭，保留现场。完工先保存原生世界，再写 COMPLETE；COMPLETE 重启只加载及核验必要落点，不重新覆盖地形。新 JVM 已验证管理员地形标记保留且 `builtBlocks()==0`。未来版本升级须明确迁移，本轮没有通用覆盖／清空命令。

四类落点由 `P1LobbyWorld.landing`／`safeLanding` 与 `profiles.placeInLobby(player, Landing)` 共用：

| Landing | 相对位置 | 使用 |
| --- | --- | --- |
| CENTRAL | (0, 0) | 首次入服、普通回厅／重连，面朝集结；与触发圈分离 |
| MONSTER | (15, 2) | 已提交极限死者进入怪物营地；自定义 Profile 测试大厅继续使用自己的落点 |
| WAIT_OUTSIDE | (-3, -6) | 入口关闭或恢复不安全时圈外等候 |
| RESTORE_READY | (0, -16) | 正常重启原准备者核验后的安全圈内恢复 |

`layoutPosition` 现在只在营地加载及施工完成后可用，实际传送仍重验落点。健康已核验大厅身体继续 Adventure 模拟，不每 tick 拉回中央、不全厅冻结。只有已受理的个人入场身份提交短暂锁定该玩家。单个档案尚未就绪／失败的 UUID 不阻止其他合格玩家准备；真正世界／存储故障保留既有隔离门槛。

`P1HumanEntry.tick` 在营地就绪及每次换轮后用原 `LobbyInteractions.registerBlock` 登记九个专用讲台。规则讲台接只读菜单，中途讲台接 ENTRY 确认；角色、庭院翻页、祭坛和历史设施已有实体位置与“尚未开放”标牌，点击仅说明关闭。三角色及庭院、祭坛、书屋业务均未提前实现。保护 Mixin 与登记类型白名单未被放宽。

### 权威准备与受理语义

- 核心 `RoundEngine.proposeAssembly(eventId, roundId, playerId, name, ready)` 将报名和准备作为一次提案、一次 SQLite 事务；`P1LobbySession.requestAssembly` 绑定轮次并使用 Runtime 安装的 `assemblyGuard`，不是先 join 后 ready。
- `P1HumanEntry` 的意图绑定当前 `ServerPlayer`／连接，维护登记中、离圈取消锁存及重新入圈状态。登记期间离圈后，即使快速重进，旧回调也先失效并持久撤销，才接受新意图；过期轮次拒绝，旧连接不获得成功提示或传送。Session 每 tick 先撤销无效准备，`proposeAdmissionTick(eventId, eligibleUUIDs)` 在归零裁决前再核验当前资格、在线、档案与位置。
- 圈内水平进入半径 6.85、保持半径 7.35，约半格迟滞；按营地基准地面核验行走安全并容许正常跳跃高度，不以转头、背包、读书或 TAB 事件撤销。首人 30 秒，后加入不重置；有效人数归零取消，下次完整 30 秒。一人开赛，最后一秒离圈仍可取消。准备成功有短音效、局部粒子、个人行动栏和“已准备 N 人”顶部条。
- 普通断线撤销个人准备；重连在圈外，不能靠 UUID 的旧 ready 位再次参赛。旧玩家 join／ready／unready 命令及菜单按钮改为场景指引，不提供空间绕过；Session 的底层接口仍是可信服务端 API。
- 中途门可以主动进入前厅或右键专用讲台，确认菜单固定到轮次及 ENTRY 上下文，并显示轮次、中文模式、挑战时间、空装自然出生点和无保护说明。确认前关闭无业务提案；确认后 `joining` 去重、Session 提交互斥，关闭不撤销已受理转换，也没有未受理自动队列。提交成功才由现有 `applyPlayers`／profiles 应用入场与参与记录；自然出生点在入口及新生命应用前重验安全。
- 极限死者在集结区被送圈外，中途门拒绝，身份不恢复人类。存活人类重连沿用原档案、位置和生命；普通死亡仍用原版重生按钮及有效床／锚，失效回本轮自然出生点，不转怪物。新专场已验证床、锚消耗一次、失效锚、末影箱与重连背包，原床破坏／锚耗尽夹具亦通过基础检查。

### 正常停服与恢复

Session 在 `prepareStop` 捕获停服时间和最终空间有效 UUID，排空已受理事务后保存 clean 检查点，不因随后 profiles 关闭而误撤全部准备。干净 COUNTDOWN 恢复先将连接标离线，保留原余量及原准备标记；`onOnline` 不再作为圈内证明。世界就绪后，Runtime 只为原 ready UUID 完成本人档案核验并恢复安全圈内站位，再调用 `restoreReady(roundId, playerId, name, safe, reply)` 续计。任一原准备者即可放行，无关 UUID 不放行，不等所有人；仍离线者不参与开赛。独立的待恢复 UUID 集合与总暂停门槛分开：首人放行后晚归的原准备者仍须完成自己的核验和圈内证明，沿用当时余量；普通 online 事件不能代替这份证明。

不安全恢复仅撤销受影响原准备者，精确放到 WAIT_OUTSIDE 并告知原因，其他原准备者仍可核验；全部取消才返回等待。恢复过程再次正常停服仍保存剩余量；过期轮次／不属于原准备者的结果拒绝。脏快照仍进入 RECOVERY_REQUIRED，没有降低已有存储、档案或恢复隔离要求。本轮没有重跑全项目 SIGKILL 矩阵，相关脏恢复由受影响的真实 SQLite Session／核心回归继续验证。

### 最终自动验证

均在仓库根目录执行，所有服务端均为新建隔离目录；无真实客户端、图形、浏览器、computer use 或真实游玩 MCP。完整命令、日志摘要和夹具边界见 [validation.json](evidence/lobby-human-entry-20261017/validation.json)。

| 命令 | 结果 | 隔离目录／证据 |
| --- | --- | --- |
| `scripts/p0-gradle.sh checkP3 -PhardrushP1=true --console=plain` | 通过；361 项 JUnit（157 core、94 persistence、110 adapter），107 项默认 GameTest | `.local/baseline-check/948b9805-6a9e-4721-85bb-c30fd2c9e351`；[最终日志](evidence/lobby-human-entry-20261017/checkP3-late-original.log)、[JUnit 汇总](evidence/lobby-human-entry-20261017/junit-summary.json) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushHumanEntry=true --console=plain` | 人类路径专场 1/1 通过 | `.local/lobby-human-entry/6c7f7e54-381d-4bee-8699-8c11f1dbdc74`；[日志](evidence/lobby-human-entry-20261017/human-entry-late-original.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyFoundation=true --console=plain` | 受影响前轮原生双连接协议 1/1 通过 | `.local/lobby-foundation/40a24f3e-2c88-471a-bb97-ff7179d35256`；[日志](evidence/lobby-human-entry-20261017/foundation-late-original.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyMovement=true --console=plain` | 移动／准备／重连／隔离／轮换 1/1 通过 | `.local/lobby-movement/3c2fffdf-3b59-4c7e-9d1b-d3a7e8d0b7ca`；[日志](evidence/lobby-human-entry-20261017/movement-late-original.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushP1TenRounds=true --console=plain` | 十轮过滤结果 2/2 通过，实际十轮完成；在晚归恢复修补前运行，不涉及重启 | `.local/p1-regression/0d62e12f-492d-4a81-a441-309d2faec056`；[日志](evidence/lobby-human-entry-20261017/ten-rounds.log) |
| `python3 scripts/p1-server-restart.py --phase COUNTDOWN` | 三个独立专用服务端 JVM 全部正常 stop、exit 0、SERVER_STOPPED PASS，SQLite 精确快照／摘要／完整性及档案封套校验通过 | `.local/p1-server-restart/a276072f-1d06-4143-a42a-233b38badd00`；[执行日志](evidence/lobby-human-entry-20261017/countdown-late-original.log)、[完整专场证据](evidence/lobby-human-entry-20261017/countdown-late-original/result.json) |

COUNTDOWN 的 writer／recover／recover-again PID 分别为 55829／55977／56095：两人原准备 → 30 减 7 保存 23 秒；无原 UUID、无关 UUID 均保持 23 秒；只有 A 完成核验并恢复圈内即可续计，推进 4 秒后 B 晚归，经本人圈内证明加入当前 19 秒且不重置，再正常停服保存 19 秒；第三 JVM 仍是同轮与 19 秒，B 的不安全圈内恢复精确落圈外且取消，A 独自恢复 19 秒并开局，访客及被取消者留厅。两次恢复均验证过期结果拒绝，营地不重建且原生地形标记保留。详见 [write](evidence/lobby-human-entry-20261017/countdown-late-original/write-evidence.json)、[recover](evidence/lobby-human-entry-20261017/countdown-late-original/recover-evidence.json)、[recover-again](evidence/lobby-human-entry-20261017/countdown-late-original/recover-again-evidence.json)。这不是纯内存时钟重启测试。

新增 5 项核心测试与扩展 Session 结果另存 [Assembly XML](evidence/lobby-human-entry-20261017/TEST-dev.hardrush.core.lobby.AssemblyTest.xml)、[Session XML](evidence/lobby-human-entry-20261017/TEST-dev.hardrush.p1.P1LobbySessionTest.xml)。人类专场从原生登录、服务端真实位置、设施包和容器确认包串到开赛、死亡／原生重生、中途加入；包含挂住 SQLite 回调后离圈快速重进、单 UUID 未就绪不影响别人、最后一秒退出、普通断线、存活重连、极限死者拒入。默认 107 项含未启用专项的占位成功，不能代替上述独立专场；十轮过滤中的庆祝占位也不算重新完成庆祝业务验收。

测试用受控单调时钟加速倒计时；床／锚预置少量测试地形，单 UUID ready 控制模拟未就绪，极限拒入前用既有审计 API 换测试模式。这些只在 GameTest 产物内，不能声称自然生存或客户端画面验收。生产 JAR（本轮构建，未发布）`platforms/mc263/build/libs/hardrush-0.1.0-p0+mc26.3.jar` SHA-256 为 `5a22274ec1be287051f15d49089023a7a2b3e2f70c366d77ec6f639a1cefae9c`，检查不含 GameTest 或 MCPFabric；本轮未发布或部署此制品。

### 人工与下一轮入口

[人工清单](人工客户端测试/大厅无指令交互与定向追猎.md) 已新增 64×48 布局、四类落点、关闭设施、幂等重启、边界／提示、单人核验、受理前后关闭等待验内容，复选框全部未勾选。布局观感、标牌可读性、实际移动与音效仍需人类用 26.3 原版客户端在隔离实例手动验证。

第 3 轮另行使用[角色／皮肤庭院与个人追猎启动词](lobby-delivery-prompts/03-direct-hunt.md)，先核对本页证据与 `P1CampBlueprint`、`P1HumanEntry` 的登记顺序。复用 `LobbyLayout.facility(ZOMBIE/SKELETON/CREEPER)`、`displaySlots()`、PREVIOUS／NEXT 锚点及 `LobbyInteractions`：在营地完成登记后、每次换轮后接管相应角色／分页回调，并使用前轮 `projections().show/turn`；当前占位回调和标牌仍关闭。个人追猎接 `P2LobbyRuntime` 的目标生命、搜索、提交与补偿请求，不复用人类中途门作为怪物入场，不恢复新规则下已关闭的 vote／dimension／deploy 旧路径。本会话未开始第 3 轮。

### 第 2 轮执行进展（保留历史）

- 收尾复核发现首位原准备者放行后，晚归原准备者缺少自动圈内恢复；已有三 JVM 成功结果尚未覆盖此分支。随后补独立的待恢复 UUID 集合、真实 SQLite 晚归测试及原生断言，并重跑基础、人类、协议、移动与三 JVM 专场全部通过；上一版摘要单独保留为 source-before-late-original.json／validation-before-late-original.json。最终证据以上表为准。

- 2026-10-17 已实现营地覆盖加载与版本施工、四类落点、权威集结和中途门；新增人类路径专场已通过（human-entry-seventh.log），包括普通床／锚／失效锚、原位置重连和极限死者拒入。之后补入单 UUID 未就绪控制与最终提示文案，再补晚归原准备者证明；最终重跑通过。
- COUNTDOWN 三个独立 JVM 专场已通过一次加严运行：`.local/p1-server-restart/672cc032-94c9-4466-b47f-8067b1132ce8`，PID 42466／42628／42752；原余量 23 秒、仅一原 UUID 恢复圈内、推进到 19 秒后二次正常停服、不安全恢复精确落圈外及过期证明拒绝。最终产品修正后还会重跑，不用此中间结果替代最终源码验证。
- 基础检查发现并保留了失败：新增安全站位复核暴露旧 Profile 空中出生夹具；已补测试站位。怪物专用落点原先错误地用于自定义 Profile 测试大厅，已限制为实际生产营地。旧 P2 锚夹具未保持出生区块可访问，已新增票据与实际安全等待，不放宽床／锚、掉落或身份断言。当时正在重跑基础检查，未标完成；最终检查与收尾补验已全部通过。
- 失败及中间日志均保存在 `docs/evidence/lobby-human-entry-20261017/`；详细范围、最终命令和摘要已填入上表。未启动真实客户端、浏览器、computer use、日常实例；未提交、推送或部署。

## 第 3 轮交接

- 状态：未开始。
- 执行会话及日期：尚无。
- 前轮复核结论与本轮基线：尚无。
- 模型 角色与个人追猎接口：尚未实现。
- 目标生命 校验 搜索预算与补偿：尚未实现。
- 投票与手动维度路径停用清单：尚无。
- 自动验证命令 结果与证据：未执行，无证据。
- 人工验收：未执行。
- 留给下一轮：完成第 3 轮后填写技能、归返、祭坛和提示可调用的身份与场景接口。

## 第 4 轮交接

- 状态：未开始。
- 执行会话及日期：尚无。
- 前轮复核结论与本轮基线：尚无。
- 无指令入口覆盖表：尚未建立。
- 物品返厅 复生 救场 书屋与状态反馈：尚未接通。
- 庆祝 轮换及原物资边界回归：未执行。
- 自动验证命令 结果与证据：未执行，无证据。
- 人工反馈与待验：尚无反馈，全部待验。
- 留给下一轮：全部主流程实现后列出稳定化矩阵、已知问题和候选包准备项；不得把首次实现主功能留给第 5 轮。

## 第 5 轮交接

- 状态：未开始。
- 执行会话及日期：尚无。
- 前四轮门槛复核：未执行。
- 普通 极限 多人 连续多轮与恢复矩阵：未执行。
- 最终问题清单与修复：尚无本轮执行记录。
- 源码与发布 JAR SHA-256：未生成。
- 依赖 安装 演示 升级与回退说明：未形成新交付记录。
- 隔离演示实例及准确命令：尚无本计划候选实例。
- 人工验收：未执行，不得标为真实客户端通过。
- 最终结论：尚不能判断开发完成或可发布。

## 每轮更新格式

前置核验通过后、修改代码前，将当前轮标为“进行中”，写入会话、日期、HEAD 和工作树基线。取得重要结果或遇阻及时更新；中断后先从本轮已有记录与源码恢复，不清空历史。必需前轮未开始或主能力未交付时，不开始当前轮，也不把整轮前置工作包含在本轮。

执行者在对应轮次章节用实际记录替换“尚无／未执行”，保留历史失败与修复结果。每条验证至少包含准确命令、环境或隔离目录、结果、日志位置与源码标识。未运行写明未运行，夹具、模拟连接、低血量首龙和测试时钟说明实际范围。

结束时同步五轮状态表，并写明下一会话应先读的接口和证据。如果部分完成，列出剩余项及前置影响；只有本轮实现和要求的自动门槛通过，才能标为“开发与自动验证完成”。人工通过、提交与部署各自独立记录。

## 文档准备记录

2026-10-16 创建五轮计划、初始交接状态和五份完整启动提示词。当前记录仅说明文档准备，不计为第 1 轮开发启动。正文中的五轮状态与产品验证结果均保持未开始或未执行。

本次完成导航、代码块、必读路径、前置边界和中断续作要求的检查。写入前后比较 1141 个非 Markdown 文件的内容摘要一致，未修改产品代码或配置；未运行构建、游戏或产品测试。本次文档未提交、推送或部署。

## 2026-10-17 文档提交

用户要求提交本会话文档。提交范围为大厅设计、需求与验收同步、26.3 五轮计划、初始交接、五份启动词及导航，不包含产品实现。庆祝流程已独立提交为 7aa1b10；本次不重复包含该改动。五轮开发仍未开始，产品构建、自动游戏测试和人工客户端验收均未执行，不推送或部署。

## 2026-10-17 第 1 轮提交

开发与自动验证完成后，用户明确要求提交。按独立意图先创建 `22ed277`（`test(identity): 等待实体区块就绪后检查原生死亡掉落`），大厅底座、规则边界、测试、完整成功与失败日志及交接文档保存于本记录所属的 `feat(lobby)!` 提交。未推送、部署或启动第 2 轮。

提交前重新逐项核对 24 个源码／测试／构建文件的 SHA-256 和最终验证日志摘要，与实际通过时完全一致；提交整理仅更新本页的提交状态，没有修改产品代码或重新宣称执行过测试。证据 JSON 中的 `head`、`dirty`、`committed: false` 描述验证时的历史状态，保持原样。两项提交的完整组合对应已验证工作树；夹具修复的中间提交没有单独执行服务端验证。人工客户端项目仍未执行。

## 2026-10-17 第 2 轮提交

开发与自动验证完成后，用户明确要求提交。营地施工、权威准备、中途入场、正常停服恢复、依赖这些行为的回归夹具及完整成功／失败证据共同服务于第 2 轮人类参赛路径，合为本记录所属的 `feat(lobby)!` 提交。没有提前接通第 3 轮角色／庭院与定向追猎，也没有推送或部署。

提交前重新核对 24 个源码／测试／构建／脚本文件的 SHA-256、五份成功检查日志及最终 COUNTDOWN 执行日志摘要，与已有验证证据完全一致。当前最终版本通过 361 项 JUnit、107 项默认 GameTest、人类／协议／移动专项，以及含晚归原准备者的三 JVM COUNTDOWN 专场；十轮回归的验证范围仍按上文记录。提交整理仅更新本页提交状态，没有修改产品代码或重新运行服务端测试。证据 JSON 的 `head`、`dirty`、`committed: false` 是验证时的历史事实，保持原样；基线与中间失败记录均保留。人工客户端验收仍未执行。
