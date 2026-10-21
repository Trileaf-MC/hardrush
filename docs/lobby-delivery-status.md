# HardRush 26.3 无指令大厅交接状态

初始化日期：2026-10-16。当前阶段：第 1～5 轮开发与自动验证完成，26.3 候选包已准备；人工验收与发布未放行。对应[五轮计划](lobby-delivery-plan.md)、[产品设计](lobby-interaction-design.md)和[启动词索引](lobby-delivery-prompts/README.md)。

## 总体状态

| 项目 | 当前状态 |
| --- | --- |
| 交付范围 | 26.3；26.1.2／26.2 正式适配另排 |
| 当前开发轮次 | 第 5 轮开发与自动验证完成；五轮交付结束 |
| 新交互开发 | 底座、人类流程、角色、皮肤庭院及个人追猎已交付；完整无指令闭环已接通 |
| 本计划的自动产品验证 | 第 5 轮 371 JUnit、110 默认 GameTest、13 独立专项、11 正常恢复 JVM、PROFILE/PREPARED 双恢复、4 生产 JVM、31 Python 通过；见第 5 轮 |
| 真实客户端人工验收 | 未执行；本轮皮肤、选取与游玩按人工清单待验 |
| Git 提交 推送 部署 | 本地提交按用户明确授权，状态见文末；未推送或部署，验证基线保持历史含义 |
| 文档准备检查 | 本轮 14 份修改文档、272 个本地链接存在性及源码／交付文档 diff 检查通过；6 份原始证据的空白原样保留，见文档检查记录；初始准备记录保留在文末 |

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
| 3 皮肤庭院与定向追猎 | 开发与自动验证完成 | 已核对前两轮主能力与证据，最终自动门槛通过 | [基线](evidence/lobby-direct-hunt-20261017/baseline.json)／[验证汇总](evidence/lobby-direct-hunt-20261017/validation.json) | 停止本轮；第 4 轮另行启动并先复核 |
| 4 全流程无指令接通 | 开发与自动验证完成 | 第 1～3 轮最新源码、日志及主能力已核验；本轮门槛通过 | [基线](evidence/lobby-full-flow-20261017/baseline.json)／[验证汇总](evidence/lobby-full-flow-20261017/validation.json) | 停止；第 5 轮另行启动 |
| 5 稳定化与候选包 | 开发与自动验证完成 | 前四轮源码与证据复核通过；最终门槛通过 | [验证汇总](evidence/lobby-stabilization-20261017/validation.json)／[候选说明](lobby-candidate.md) | 用户按人工路线验收；五轮结束，不自动发布 |

后轮未开始不等于技术失败。第 1～3 轮执行发现的失败与修复见下文；三轮必需自动门槛已通过，人工画面与后轮业务仍未验收。

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

- 状态：开发与自动验证完成。限 26.3 的角色／皮肤庭院／个人追猎；真实客户端未验收，第 4～5 轮未开始。
- 会话及日期：01a14833-071b-73b3-9e4e-c5640b63a157，2026-10-17。
- 基线：main，HEAD `773168490f688416b485cd9e864c094dc7165f98`，启动工作树干净；适用 AGENTS.md 仅仓库根文件。[baseline.json](evidence/lobby-direct-hunt-20261017/baseline.json) 保存启动源码摘要、Git 状态和前置复核。前两轮合并后 38 个最新源码文件及 9 份成功日志逐项核对一致，主能力与接口已复读，没有替代执行整轮前置。
- 最终源码：本轮 28 个源码／测试／构建文件见 [source-files.json](evidence/lobby-direct-hunt-20261017/source-files.json)，聚合 SHA-256 `34d8209a4252fab9fe2c0dc88451864f6f9a9b1962ede4cd51ddb009515a7e09`。生产源码与各专项对应源码在最终验证间逐项核对一致；P3 夹具收尾范围见 validation.json。验证时仍是上述 HEAD 上的未提交工作树，随后本地提交另记。

### 角色 模型与个人请求接口

`P1HumanEntry` 沿用第 2 轮专用讲台登记和双保护路由，将 ZOMBIE／SKELETON／CREEPER／PREVIOUS／NEXT 交给 `P2LobbyRuntime.facility`。只有当前连接、已核验大厅身体、极限新规则与进行／救场阶段开放；选角还要求在线 MONSTER_LOBBY 且没有搜索或提交。`P1LobbySession.requestMonsterRole` → `RoundEngine.proposeMonsterRole` 只改角色，保留技能、出场冷却、生命／出场序号与历史维度字段。角色成功后给出勾选及技能简述，菜单只指引场景。既有营地仅更新拥有的标牌文字，不升级布局、不重建地形。

庭院复用 `LobbyProjections.show/turn/view/aimed`。本轮展示名册按 UUID 建表后只追加，离线、死亡及复生不回收或重排既有槽；死亡显示 40 tick 后留空，同 UUID 新生命返回原槽。每页 20 个槽，可独立翻页。原 `GameProfile` 直接进入全身 Mannequin，未取得皮肤资料时保留 UUID／名字和默认外观。只复制原 profile、固定展位姿态与受控状态，完全不读取目标装备、血量、背包、坐标、朝向或距离作为展示信息。模型只发给本人连接，从未加入世界实体、PlayerList、参赛或团灭计数。

AVAILABLE／AWAY／DEAD／EMPTY／RESTORING／CLOSED 区分可追猎、暂离、死亡、空位、复生核验及不可用。复生持久提交后的 offline 位不再压过“复生核验中”；必须档案和在线均完成才恢复 AVAILABLE。映射继续绑定观察者对象、连接、轮次、代次、实体 ID、目标 UUID 和生命；更新用全新 ID，旧页面与旧连接不能重绑新目标。旧菜单也绑定连接。瞄准提示使用 TARGET 优先级，搜索／提交使用 PROGRESS；冷却结束只提示一次，绝不登记自动出场。

模型右键经两层保护统一路由进入私有 `beginDeployment`，以 `PersonalHuntRequest(round, hunter, hunterLife, initialSortie, kind, target, targetLife)` 冻结双方身份，另绑定双方原生身体／连接。搜索每 tick、`requestPersonalHunt` 的核心提案、提交回调及传送前都复核。补偿后的当前出场序号单独推进，原始身份和目标生命不变。同 UUID 复生、连接更换、死亡或离线均不能继承旧请求。

### 安全 预算 补偿与旧路径停用

2026-10-21 后续规则覆盖：追猎距离已缩短为目标水平 24～40 格、距所有同维度在赛人类至少 16 格；地形按实际玩家身体及原版地面语义放宽，不再要求周边平地或可达路线，玩家怪物不因日晒燃烧。以下原批次的距离与地形描述保留为历史，当前规则见 `requirements.md`。

按同一目标当前所在的本轮维度搜索；同一生命换维度和安全点失效均共享原始 `started`、已用候选及剩余额度。每次最多 2 秒／累计 64 候选，既有每 tick 4 候选／2 ms 切片、32～64 格目标水平距离、所有同维度人类至少 24 格及全部地形规则保持。测试竞技地形由夹具预置，生产搜索没有加载区块、制造地形、贴脸兜底或随机换人。失败后 `retryAt` 按发起怪物 UUID 等待 3 秒，换目标／重连不绕过。

只在 SQLite 出场身份提交成功后应用档案、传送和角色，真实角色未应用也不能报成功。目标移动、地形或维度变化导致传送前失效时，对精确生命／出场序号执行内部取消；排队的死亡／断线写入只让补偿等待，不丢失退款。补偿成功恢复出场机会，仍在原预算内才重搜同一目标。真实世界／档案异常继续隔离，SQLite 写入失败不应用出场。出场成功后移除临时目标请求，不自动追随；攻击其他合法人类及按实际致死归属获得资格均保持。

停用清单：

- 生产 Runtime 不再创建、同步或调度 `HuntVotingService`，没有 15 秒结算、票数、共同胜者或随机重选。
- 新规则的 `vote`、`dimension`、无目标 `deploy` 命令及直接 action 均只给场景指引，不发起搜索；维度补全和对应菜单选项已移除。
- GUI 旧投票人头、维度、共同目标、出场按钮已移除；选角菜单／命令引导到实体设施，目标只能由服务端模型映射受理。
- `restartSearch` 只使用冻结 UUID／生命的同一目标，`monsterDimension` 不参与角色保存或追猎路由。原字段、旧编码和规则指纹保留，没有重写历史。
- 低层 `proposeMonsterDeploy`／`requestMonsterDeploy` 保留为受信任的历史／内部身份事务原语，玩家入口不调用；当前业务必须先经定向提案。历史投票单元测试仍只证明旧服务，不能算新 S03。
- P2 实战专场和 P3 强杀夹具的出场改为读取实际模型出站包并输入实体右键；通用 GUI 夹具改为只读按钮，不再冒充投票。原生登录、死亡、档案、资格与掉落断言保留。

### 最终自动验证

全部从仓库根目录执行，所有服务端均是新建隔离目录；没有真实客户端、图形、浏览器、computer use、真实游玩 MCP 或日常实例。完整结果和日志摘要见 [validation.json](evidence/lobby-direct-hunt-20261017/validation.json)，JUnit 明细见 [junit-summary.json](evidence/lobby-direct-hunt-20261017/junit-summary.json)。

| 准确命令 | 结果 | 实际隔离目录与日志 |
| --- | --- | --- |
| `scripts/p0-gradle.sh checkP3 -PhardrushP1=true --console=plain` | 367 项 JUnit + 108 项默认 GameTest 通过 | `.local/baseline-check/b5ec431a-e55f-4db5-83bf-9c66f147907c`；[checkP3-complete.log](evidence/lobby-direct-hunt-20261017/checkP3-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushP2Lobby=true --console=plain` | 1/1 通过 | `.local/p2-lobby/94626d90-bf55-4315-9547-9580dd06e244`；[verified-p2-final.log](evidence/lobby-direct-hunt-20261017/verified-p2-final.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushPersonalHunt=true --console=plain` | 1/1 通过 | `.local/personal-hunt/aa0486ed-9e69-446b-bc1f-2bd85b412990`；[verified-personal-final.log](evidence/lobby-direct-hunt-20261017/verified-personal-final.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushPersonalHunt=true -PhardrushPersonalFault=storage --console=plain` | 1/1 通过 | `.local/personal-hunt/41de4411-7290-4af7-b674-e019cf23e773`；[verified-personal-storage-final.log](evidence/lobby-direct-hunt-20261017/verified-personal-storage-final.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyFoundation=true --console=plain` | 1/1 通过 | `.local/lobby-foundation/b1656c2d-1e59-4980-9adb-344482d24256`；[verified-foundation-final.log](evidence/lobby-direct-hunt-20261017/verified-foundation-final.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushHumanEntry=true --console=plain` | 1/1 通过 | `.local/lobby-human-entry/556db877-9eda-4adc-83b7-6e2d963c12ec`；[verified-human-final.log](evidence/lobby-direct-hunt-20261017/verified-human-final.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyMovement=true --console=plain` | 1/1 通过 | `.local/lobby-movement/cf88f92c-109e-4e76-bfea-6edc11b068fb`；[verified-movement-final.log](evidence/lobby-direct-hunt-20261017/verified-movement-final.log) |
| `python3 scripts/p3-server-crash.py --scenario AWARD --boundary COMMIT` | 独立 JVM 场景 通过 | `.local/p3-server-crash/b04721a1-5650-435a-a8f2-f6d10a1560df`；[p3-award-commit-verified.log](evidence/lobby-direct-hunt-20261017/p3-award-commit-verified.log) |
| `python3 scripts/p3-server-crash.py --scenario PROFILE --boundary PREPARED` | 独立 JVM 场景 通过 | `.local/p3-server-crash/d4f2f73b-afa3-4880-98b6-2aee4dd55729`；[p3-profile-prepared-verified.log](evidence/lobby-direct-hunt-20261017/p3-profile-prepared-verified.log) |

专项过滤器在 `platforms/mc263/build.gradle` 明确声明，并用统一互斥检查禁止同 JVM 混跑：

- `-PhardrushP2Lobby=true` → `hardrush-gametest:p2runtime_lobby_game_test*`。
- `-PhardrushPersonalHunt=true` → `hardrush-gametest:p2personal_hunt_game_test*`。
- `-PhardrushLobbyFoundation=true` → `hardrush-gametest:lobby_foundation_game_test*`。
- `-PhardrushHumanEntry=true` → `hardrush-gametest:p1human_entry_game_test*`。
- `-PhardrushLobbyMovement=true` → `hardrush-gametest:p1lobby_movement_game_test*`。

`hardrushP1=true` 启用正式 Runtime；P2／个人追猎以 hardcore 启动，人类／底座／移动以 normal 启动。`hardrushPersonalFault=storage` 只改变隔离测试的故障注入，默认 adapter。默认 GameTest 没有专项 filter，108 项包含未启用专项的占位成功；专项证据单独计算。367 JUnit 为 core 163、persistence 94、adapter 110，新增 6 项定向请求测试；[定向 XML](evidence/lobby-direct-hunt-20261017/TEST-dev.hardrush.core.hunt.PersonalHuntRequestTest.xml) 和[角色 Session XML](evidence/lobby-direct-hunt-20261017/TEST-dev.hardrush.p1.P2MonsterSessionTest.xml) 可直接复核。

个人专项使用四个原生嵌入连接和真实 SQLite／档案 IO，另预置 18 名离线参与记录，仅验证两页稳定陈列。覆盖极限死亡、设施选角、模型右键、三维度安全出场、败退、同／不同目标、双手和连点、旧页面／连接、共享 2 秒及累计恰好 64 候选、3 秒换人限制、提交后移动／地形取消补偿、离线、实际死亡与复生、两次核验状态，以及击杀非所选人类的资格。既有 P2 专场保留并通过 20 次击杀／返回／复生循环；返回／技能／复生仍是原业务回归，不宣称实现第 4 轮入口。

单调挑战／搜索时钟受控，三维度竞技地形预置，提交回调由夹具挂住后改变条件；SQLite 错误由真实触发器拒写产生，适配器错误为受控注入。这些不是 20 个图形客户端、自然地形满流程游玩、皮肤下载或真实介质故障验收。正常 COUNTDOWN 三 JVM 矩阵及整个 P3 强杀矩阵没有全量重跑，既有证据保持；本轮只运行上表列出的直接受影响场景。

AWARD/COMMIT 的实际 PID 为 19714（SIGKILL）→19888→20000，PROFILE/PREPARED 为 20385（SIGKILL）→20584→20684；四个恢复 JVM 均 exit 0 且仍隔离。分别保留[资格事务结果](evidence/lobby-direct-hunt-20261017/p3-award-commit-result.json)、[档案边界结果](evidence/lobby-direct-hunt-20261017/p3-profile-prepared-result.json)、[资格写入与 WAL 边界](evidence/lobby-direct-hunt-20261017/p3-award-commit/write-evidence.json)和[档案写入边界](evidence/lobby-direct-hunt-20261017/p3-profile-prepared/write-evidence.json)。旧轮三维度／档案与大厅 COMPLETE 清单保持精确摘要；共享大厅保持运行，原生恢复另验证不重建和青金石标记，region／POI 保存字节不被冒充为冻结旧轮文件。

生产 JAR（本轮构建，未发布）`platforms/mc263/build/libs/hardrush-0.1.0-p0+mc26.3.jar` SHA-256 `9ede3d56ab63dfc12874e421184a21168519bf46d325002bdce8064401e3d88a`；检查不含 GameTest、MCPFabric 或 debug tools。没有部署或推送。

### 人工与第 4 轮交接

[人工清单](人工客户端测试/大厅无指令交互与定向追猎.md) 已更新角色标牌、原皮肤降级、后排／边角选取、死亡空位／复生恢复、独立分页、旧连接、冷却提示与追猎音效，全部未勾选。真实皮肤下载／渲染、鼠标选取与实际游玩继续待人工。

第 4 轮另行使用[完整无指令闭环启动词](lobby-delivery-prompts/04-full-flow.md)。复用 `P2LobbyRuntime.facility` 的登记模式、当前 MONSTER_LOBBY／MONSTER_DEPLOYED 与生命／出场标签；技能继续通过 `MonsterRoles`／`P2MonsterRuntime` 的正式持久冷却，归返物品接 `beginReturn`／`interruptReturn` 的既有引导业务，先解决装备维护与输入中断顺序。祭坛由 ALTAR 现有讲台接确认并调用 `requestRevival`，不能要求出场冷却归零；复生展示标记只供 UI，不能授予资格。提示复用 `router.hints` 的 TARGET／PROGRESS／RECOVERY 仲裁。书屋、祭坛确认、技能与归返物品、救场／庆祝提示整合均尚未接通，不在本轮扩大实施。

### 第 3 轮执行进展（历史保留）

以下是执行时保存的进度、失败与解除记录，以本节最终结论为准。

- 状态：进行中。仅实施第 3 轮；第 4～5 轮未开始。
- 会话及日期：01a14833-071b-73b3-9e4e-c5640b63a157，2026-10-17。
- 基线：main，HEAD `773168490f688416b485cd9e864c094dc7165f98`，启动工作树干净；适用 AGENTS.md 仅仓库根文件。未清理或覆盖既有成果。见 [baseline.json](evidence/lobby-direct-hunt-20261017/baseline.json)。
- 前置核验：前两轮已交付主能力；合并两轮 manifest 后逐项核对当前最新源码，所有成功日志摘要一致。已复读统一双保护路由、原 profile／连接／轮次／代次／生命映射、营地设施登记、权威集结及恢复接口；没有替代执行前轮。
- 本轮实施：角色设施、UUID 稳定庭院、原皮肤模型、冻结生命的个人请求、跨维度共享预算及精确补偿已接通；P2、菜单及 P3 出场夹具已迁移。源码已冻结，最终自动验证进行中。
- 自动验证进展：新增 6 项核心定向生命／补偿／角色测试已通过；迁移后的 P2 真实 SQLite／档案／原生输入专场 `p2-second.log` 1/1 通过，包含 20 次既有击杀／资格／返回／复生循环。新增四连接专项正在验证，未达本轮完成门槛。
- 已保留失败：首次旧 P2 夹具在同一 tick 加速冷却后读取 committed 快照而非实时 view，未能发起出场；改读权威 view 后重跑通过。角色／维度旧夹具迁移、26.3 API 编译差异及新增协议断言只统计 searches 而遗漏 pending 的失败均保留日志，未削减产品约束。
- 补偿改造：提交后的取消按精确生命／出场序号保留，遇已排队死亡／断线写入等待重试；真实存储／档案失败仍走隔离。
- 四连接专项已通过三维度、同／不同目标、连点、累计 64 候选／2 秒、目标移动／地形变化后补偿、离线、复生、新生命及非目标人类的真实击杀资格；生产分页另预置 18 名离线记录，旧页面与旧连接拒绝通过。受控适配器故障及 SQLite 触发器真实提交失败均未开放出场。最后补角色应用成功复核和单次冷却结束提示／音效后，正重跑冻结版本的完整门槛。
- 分页／旧连接补验的中间失败 `personal-fourth.log`：重连夹具在 online 提交前推进冷却时钟，在线计时未流逝；改为等待 online 提交后推进，`personal-fifth.log` 与 `personal-storage.log` 已通过。该超时终止的独立服务端及日志保留，没有改冷却规则或触碰日常实例。
- 人工：真实皮肤、选取与游玩全部待人工；不启动真实客户端、图形或浏览器。自动场景只用新隔离目录。
- 最终回归进展：冻结版基础检查（367 JUnit、108 默认 GameTest）及 P2、个人追猎／真实 SQLite 失败、底座协议、人类入场、移动专项均通过；迁移的 AWARD/COMMIT 强杀夹具已到实际 COMMIT 边界，后续独立 JVM 恢复核对中。
- 收尾复核：复生提交的短暂 offline 位原先会抢先显示“暂离”；已增加仅供展示的复生核验标记，优先显示“复生核验中”，身份／档案／在线门槛通过后才可追猎。新增两次原生复生状态断言，最终源码及全部受影响门槛正在重跑，尚未标完成。
- 前轮局部直接缺陷（第 2 轮空间夹具归属）：P3 的 AWARD/COMMIT writer 已经从新模型入口到达实际 WAL COMMIT 并被 SIGKILL；首次 recover 在正确的恢复隔离中被旧 `P1SpatialFixtures.handle(join)` 的“菜单必须打开”假设错误终止。新增 `closedGate` 以真实受保护讲台包验证入口保持关闭，P3 普通与 Profile 两个恢复调用点共用；未修改生产恢复规则、资格或存储判断。失败证据见 `p3-award-commit.log` 和 `p3-award-commit-result-before-state-and-gate-fix.json`，独立目录保留。
- 恢复证据范围复核：第二次 P3 执行中 reader 的精确身份／SQLite 校验已通过，但父进程把运行中共享大厅的原生 region／POI／标牌保存字节误列为冻结旧轮文件，导致摘要比较失败。实际变化只在 `dimensions/hardrush/lobby`，旧轮三维度与档案均未变。现保留旧轮世界／档案全部字节核验，增加大厅 COMPLETE 拥有权清单摘要及恢复原生 `builtBlocks()==0`／青金石地形标记断言。`p3-award-commit-verified.log` 中三个独立 JVM 已通过（19714 被 SIGKILL、19888／20000 正常恢复并保持隔离）；另补 Profile/PREPARED 及最终基础复核，尚未收尾。
- 提交：用户本次明确授权完成后本地提交；未授权推送、部署，不启动第 4 轮。

## 第 4 轮交接

- 状态：开发与自动验证完成。仅执行第 4 轮；第 5 轮、真实客户端、推送及部署未启动。
- 执行会话及日期：01a1487c-247a-79d3-9924-63a39e2ba57c，2026-10-17。
- 前轮复核结论与本轮基线：main，HEAD `8b0c42fba323ad341d39a301366c1cc7b0ce242d`，工作树干净；仅根 AGENTS.md 适用。前 3 轮合并后的 54 个最新源码及 17 份验证日志摘要一致，已复读双保护、菜单上下文／提示、空间集结、角色与个人追猎接口，前置主能力已交付。完整文件基线见 [baseline.json](evidence/lobby-full-flow-20261017/baseline.json)。没有重建状态文件、覆盖已有改动或依赖聊天历史。

### 玩家动作覆盖表

下表均为已接通实现；最终自动通过状态以下方冻结源码验证表为准，不代替人工客户端验收。

| 玩家动作 | 场景／物品／界面 | 权威接口与约束 |
| --- | --- | --- |
| 首次入服与查看可用动作 | 中央广场、状态牌、行动栏、TAB | 原档案核验；不自动集结 |
| 报名、准备、取消 | 集结圈入圈／离圈 | `P1HumanEntry`／`requestAssembly`；归零终验、普通断线取消 |
| 挑战中参赛／救场新人入场 | 中途门讲台／前厅确认 | `ENTRY`／`requestJoin`；确认前关闭不加入，受理后关闭不撤销 |
| 普通死亡与重生 | 原版死亡按钮、床／锚 | 原生重生＋档案生命周期；不进极限怪物流程 |
| 极限死亡、怪物败退 | 原生死亡／替换身体、怪物落点 | 原死亡批次、掉落、档案转换与 30 秒冷却 |
| 选角 | 三角色展位讲台 | `facility`／`requestMonsterRole`；角色冷却不重置 |
| 选人、翻页、出场 | 个人皮肤庭院模型、上一／下一页讲台 | 原 `PersonalHuntRequest`；同一生命、共享预算与补偿 |
| 获知无人可追猎 | 暂离模型、行动栏、TAB | 依据核心人类资格；离线保留，只暂停挑战计时 |
| 使用僵尸／骷髅／苦力怕技能 | 原固定快捷栏物品 | `MonsterRoles.useControl`／正式持久技能冷却；说明与冷却名称 |
| 主动归返 | **快捷栏第 8 格回响碎片** | 唯一 `repairLoadout` 来源，`useReturn`→原 Channel→`requestMonsterReturn` |
| 理解归返进度／取消 | 5 秒行动栏、聊天及短音效 | 移动、攻击／空挥／方块攻击、物品／技能、受伤均中断；引爆、连射／蓄弓／技能提交期间拒绝启动 |
| 查看本人资格 | 复生祭坛讲台、个人确认页 | `REVIVAL` 上下文；只读资格，不自动消费 |
| 空装复生 | 祭坛“确认复生／继续作为怪物” | 轮次＋生命＋出场值、身体／连接与距离重验；原 `requestRevival`／档案转换；冷却不拦截 |
| 救场恢复 | 60 秒顶部条、祭坛和中途门 | 原死亡批次先发资格／处理自爆再裁决；祭坛与新人入口分别可恢复 |
| 阅读规则 | 规则讲台与只读菜单 | 模式、集结、追猎、技能、复生、胜负；准备／健康恢复期间按服务门槛开放 |
| 找轮次、看成绩／名单 | 书屋→轮次分页→详情／名单分页 | `P1Library`＋共享 `P1HistoryQueries`；每页有界，未结算种子不公开 |
| 查看分模式／规则榜 | 书屋普通／极限按钮、历史轮自身规则榜 | 仍按完整规则指纹查询；异常／未核验成绩不入正常榜 |
| 领取主册／分卷、满包重试 | 书屋详情／分卷目录 | `HistoryBookService.claim` 复用 `book_deliveries`；先写待领，库存保存后 ACK；待领重试同 operation ID，已交付后新副本仍限速 |
| 阅读成书与补发最新主册 | 手持成书右键、书屋“阅读已领取书册”、原自动待领机制 | 原 `P1BookPort`／库存持久化核验；成书与失败文案指向书屋，无必须输入命令 |
| 首龙通关与庆祝 | 原生有效攻击、末地固定名单与临时物品 | 复用 `P1CelebrationRuntime`／档案 journal；名单包含人类及怪物，最多 180 秒 |
| 提前返厅与重连 | 原生龙池出口 | 原物资封存、returned 持久标记；已返回者重连留独立大厅 |
| 安全换轮、开始下一轮 | 全员在线返回／到期，圈外落点 | 仍等待档案／回放／原生世界关闭；新轮需主动重新入圈 |

### 本轮接口与实现边界

- `LobbyContext.REVIVAL` 接入原 `P2LobbyUi` 的轮次、当前身体／连接、按钮来源和 250 ms 防连点；祭坛按钮另绑定生命／出场序号，出生不安全保留资格，真实事务／档案错误保持隔离。竞技菜单旧复生按钮改为祭坛指引；旧 vote／dimension／无目标 deploy 路径继续停用。
- `MonsterRoles.isReturnControl` 只认服务端角色与主手固定槽，不凭客户端名字授权；先分流归返，再中断普通物品／技能。物品不能由另一个 tick 维护器注入，角色装备与控制物仍不可丢弃、换装或转赠。
- `P1PlayerFeedback` 在所有意图生产者及异常／庆祝短路之后统一渲染，明确清理已失效的核验／追猎提示租约。优先级继续是恢复 > 执行进度 > 准备 > 目标 > 设施。顶部救场／庆祝条有独立生命周期；`P1TabRuntime` 保持只发原生页头页脚，不改真实在线玩家条目。
- `P1HistoryQueries` 将有界查询与安全文字展示提取给命令和书屋共用；`P1Library` 视图绑定身体、连接、当前轮次及查询代次，迟到 IO 不重开界面或更新新连接。每批轮次／名单 20 条加 1 条翻页探针，分卷目录也有界。不执行玩家命令再解析聊天。
- 书屋显式领取使用新增 `HistoryBookService.claim`，复用原 `Claims`、`book_deliveries`、`P1BookPort` 和库存回读核验。没有新增存储系统或重写资格／档案架构。原 `manual` API 的历史兼容语义及其测试保留。
- `P1HumanEntry.readOnlyTick` 在 Runtime 异常短路前登记规则／历史，只读能力与竞技门槛分开；数据库 FAILED／RECHECKING 或个人真实隔离不开放。读不到历史明确失败，不伪造空记录。
- 庆祝主机制已由独立工作交付，本轮复用并验证怪物名单、临时物资、原生龙池和下一轮边界，只补无指令引导与统一展示；未建立第二套庆祝记录。

- 物品返厅 复生 救场 书屋与状态反馈：已实现，最终自动门槛通过。回响碎片固定第 8 格并由 MonsterRoles 维护；祭坛绑定轮次／生命／出场并确认消费；书屋共用有界查询与文本服务，不执行命令。顶部救场／庆祝条和个人行动栏仲裁已接通，保留既有事务及档案架构。
- 执行中证据：`full-flow-fourth.log`／`full-flow-fifth.log` 已走过真实集结、极限死亡、模型追猎、物品中断与归返、重复祭坛确认、救场恢复和苦力怕最后一击资格／回厅顺序；这些是中间结果，不能计为最终通过。首次编译修正测试端 Level 类型；进度断言暴露旧核验／追猎提示租约未语义清理，已在统一渲染时清理失效来源（前轮局部提示缺陷，本轮整合归属）；另修夹具死亡替换未等待及终局观测时钟未同步，失败隔离目录与完整日志保留。
- 庆祝 轮换及原物资边界回归：完整闭环、独立庆祝专项及 CELEBRATING 正常重启补验全部通过，见下表。
- 前轮局部直接缺陷归属：既有 `MonsterCombatPlayerMixin` 在 `Player.attack` HEAD 直接封锁正式怪物，可能抢在 Fabric 同位置的攻击回调前取消，真实左键无法到达怪物模型。旧 P2／第 3 轮循环调用 `MonsterRoles.attack`，未覆盖该包路径。本轮改为两条 HEAD 路径都转交同一受控怪物服务且各自终止 vanilla，先执行者完成唯一操作；仍受既有身份、距离、冷却及来源白名单限制。原生包专项的真实失败保留为 `full-flow-owned-reader-melee-failure.log`；此修补不替代整轮前置。
- 底座夹具迁移：旧“历史未接通”断言已改为重核后实际书屋讲台可读，竞技菜单仍拒绝；新增等待只读查询排空后再审计结束恢复轮，并断言迟到查询不重开已关闭菜单。原两次失败分别保留 `foundation-history-expectation-failure.log` 与 `foundation-history-drain-failure.log`，没有放宽管理 IO 门槛。此后仅该 gated 夹具改变，前四项最终产品／专项代码摘要逐项保持。
- 收尾复核（历史保留）：新增待领分卷探针曾错误使用只返回 volume=1 的 `latestAutomaticBookClaim`，现用只读精确 SQL 核对 volume=2 的持久 pending／ACK，原失败保存为 `full-flow-ledger-probe-failure.log`。随后发现深背包的成书在大厅库存封锁下缺少阅读动作，已接书屋“阅读已领取书册”→原 `P1BookReader` 并补原生 OpenBook 包断言；旧版本四项通过日志和摘要保留为 `*-before-owned-reader.log`／`checks-before-owned-reader.json`，不冒充最终源码门槛。
- 自动验证命令 结果与证据：最终 368 JUnit、109 默认 GameTest、九项原生专项与庆祝两 JVM 正常重启全部通过；完整命令、隔离目录及日志摘要见[验证汇总](evidence/lobby-full-flow-20261017/validation.json)和下表。
- 夹具修正：第 7～9 次中间运行部分由本会话对确认属于自身 `.local/lobby-full-flow` 目录的子进程发送 SIGTERM 停止，完整日志保留，不能计成功。界面输入遵守原 250 ms 节流；重连后的原生监听器必须随嵌入连接 tick，避免原版登录保护／物品时钟永远停住。最新夹具走实际原生监听器，未放宽保护或改玩家核心身份。
- 人工反馈与待验：本会话未收到新反馈；[人工清单](人工客户端测试/大厅无指令交互与定向追猎.md) 已补固定第 8 格、各种中断、冷却中确认、读书／分卷、待领、健康异常只读、庆祝与下轮步骤，全部未勾选。没有启动真实客户端、浏览器、computer use、游玩 MCP 或日常实例。

### 最终自动验证

全部本轮门槛已通过。完整闭环 SQLite 复核见 [full-flow-sqlite-audit.json](evidence/lobby-full-flow-20261017/full-flow-sqlite-audit.json)，成功／失败日志与中间版本摘要全部保留。

| 准确命令 | 结果 | 隔离目录／证据 |
| --- | --- | --- |
| `scripts/p0-gradle.sh checkP3 -PhardrushP1=true --console=plain` | 通过；368 JUnit（164 core＋94 persistence＋110 adapter）、109 默认 GameTest | `.local/baseline-check/65d668d7-e551-4d98-882a-08ee2431d9b2`；[checkP3-verified.log](evidence/lobby-full-flow-20261017/checkP3-verified.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushFullFlow=true --console=plain` | 通过；1/1 过滤结果 | `.local/lobby-full-flow/cdbaf83f-91c0-4a04-9fd4-368417609e97`；[full-flow-complete.log](evidence/lobby-full-flow-20261017/full-flow-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushFullFlow=true -PhardrushFullFlowFault=revival --console=plain` | 通过；1/1 过滤结果 | `.local/lobby-full-flow/aec64771-1d83-4bbc-afbf-fee61a2881a3`；[full-flow-revival-fault-complete.log](evidence/lobby-full-flow-20261017/full-flow-revival-fault-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushP2Lobby=true --console=plain` | 通过；1/1 过滤结果 | `.local/p2-lobby/73a9f701-a07b-465e-bd5e-1186bd1f3522`；[p2-complete.log](evidence/lobby-full-flow-20261017/p2-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushCelebration=true --console=plain` | 通过；2/2 过滤结果 | `.local/celebration-regression/81695922-1f7b-4aa8-99e5-84f11e998085`；[celebration-complete.log](evidence/lobby-full-flow-20261017/celebration-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyFoundation=true --console=plain` | 通过；1/1 过滤结果 | `.local/lobby-foundation/64724dc4-6bca-40cb-8f38-66736dc36d5e`；[foundation-complete.log](evidence/lobby-full-flow-20261017/foundation-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushHumanEntry=true --console=plain` | 通过；1/1 过滤结果 | `.local/lobby-human-entry/a0c9f3eb-1dca-46a2-8175-caa9496132c4`；[human-complete.log](evidence/lobby-full-flow-20261017/human-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyMovement=true --console=plain` | 通过；1/1 过滤结果 | `.local/lobby-movement/1452b7b0-d5f5-43b1-a40a-c3c207ab4ed8`；[movement-complete.log](evidence/lobby-full-flow-20261017/movement-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushPersonalHunt=true --console=plain` | 通过；1/1 过滤结果 | `.local/personal-hunt/47d183c8-45ac-4636-a131-ca5f12b0f717`；[personal-complete.log](evidence/lobby-full-flow-20261017/personal-complete.log) |
| `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushPersonalHunt=true -PhardrushPersonalFault=storage --console=plain` | 通过；1/1 过滤结果 | `.local/personal-hunt/38f6d02e-4396-441d-9dae-eccb9c25ec96`；[personal-storage-complete.log](evidence/lobby-full-flow-20261017/personal-storage-complete.log) |
| `python3 scripts/p1-server-restart.py --phase CELEBRATING` | 通过；writer／recover 两个独立 JVM 正常停止，exit 0；SQLite／封套核验通过 | `.local/p1-server-restart/e7df8f58-1228-4857-9a6b-cad25818ed93`；[celebration-restart.log](evidence/lobby-full-flow-20261017/celebration-restart.log) |

所有命令从仓库根目录执行，每次均为新隔离目录，没有清空日常存档。109 默认结果包含未启用专场的占位；庆祝过滤 2 项中十轮方法未启用，不因此宣称本轮重新完成十轮。九个独立专项分别证明实际相应入口，不能将默认占位叠加成完整闭环证据。

本轮 26 个源码／测试／构建文件 SHA-256 与[最终清单](evidence/lobby-full-flow-20261017/source-files.json)一致，聚合 `7626fa34c746c4d193aa1b36202958367a410bdf1c076b33f1f0b9f54ef461b9`。前四个原生专项后只迁移 gated 的底座历史／只读排空夹具；产品、对应专项与共享夹具逐项保持，最终基础及底座专项已在最终源码重跑。验证时仍是基线 HEAD 上的未提交工作树；不能单用该 HEAD 当实现版本。

完整无指令专场由三条原生嵌入连接通过位置、讲台、实体右键、快捷栏物品、容器确认和原生攻击包串起全部业务：资格与救场先后、主动返厅、两次祭坛复生、苦力怕最后人类击杀／自爆、两条救场恢复路径、末地人类＋怪物庆祝、龙池返厅、原生世界关闭和下一轮主动集结、历史与读书。没有执行旧玩家命令或调用核心改身份。另以 22 条合成 ABORTED 历史、每轮 42 条参赛记录验证分页／分卷；它们不是游玩或通关成绩。卷 2 的满包待领和后续 ACK 已核对真实 SQLite 行；实际首轮正常榜资格、完整性和外键检查通过。

夹具范围：预置有界暗棚竞技场、原生致死前置、低血量人类与首龙、受控挑战／伤害单调时钟、归返引导年龄及领书重试限速时钟；界面输入遵守原 250 ms 节流。归返移动、攻击、普通物品／技能和受伤中断均由正式输入验证，物品每 tick 修复仍存在；5 秒成熟由测试时钟加速，不冒充实际五秒客户端手感。SQLite 触发器故障是受控拒写，不是物理磁盘验收。

庆祝独立专场另外验证重连余量、returned 留厅、其他维度回厅、全员返回、离线不阻塞、180 秒受控到期和临时物品清理。CELEBRATING 正常重启 PID 为 64668→64813，两次均正常 stop／exit 0；同轮 UUID、已冻结 13 秒成绩和剩余 60 秒保持，档案封套、SQLite 精确快照与完整性通过，见[重启结果](evidence/lobby-full-flow-20261017/celebration-restart/result.json)。没有扩大为所有阶段／强杀矩阵或恢复可玩宣告。

生产 JAR 仅作为构建验证，SHA-256 `dde5a4d2374448a93aaa3d6b133abfa64b93cd848dc0fc512b29530ef47d8873`；[制品检查](evidence/lobby-full-flow-20261017/jar-inspection.json)确认无 GameTest、MCPFabric 或 debug 类。未生成部署候选包、发布、推送或部署；回放 disabled 不算录像验收。人工画面、皮肤、声音与完整游玩仍全部待验。


### 交给第 5 轮的矩阵与候选包准备项

第 5 轮尚未开始；仅在本轮全部自动门槛通过后另行启动[第 5 轮启动词](lobby-delivery-prompts/05-stabilization.md)。所有玩家主业务本轮首次实现，不将返厅、祭坛、历史分页或庆祝接线留给后轮。

| 第 5 轮回归矩阵 | 本轮可复用入口／证据 | 重点 |
| --- | --- | --- |
| 普通／极限、多玩家、连续轮换 | FullFlow、P2、Celebration、人类专项 | 不串生命、资格、背包或自动准备 |
| 归返和技能 | 物品、空挥、方块、实体及容器包 | 重连、正常停服、换维度、冻结与旧回调清理 |
| 资格与救场 | 祭坛、真实 SQLite 拒写、死亡批次 | 到期受理顺序、离线召唤物、出生变化与恢复隔离 |
| 书屋与大历史 | 20 条翻页探针、分卷、待领 ACK | 多观察者、修订书版、重连／关闭 IO、库存保存故障 |
| 庆祝与释放 | 原固定名单、龙池、独立正常重启 | 临时余量、returned、原物资、回放保存和旧世界 IO |
| 旧规则／过期请求与约 20 连接 | 原协议／个人追猎专项 | 代次、轮次、连接清理；不是图形客户端体验验收 |

已知限制：真实原版画面、皮肤、准星／物品手感、音效与完整游玩仍待人工；没有新人工通过结果。约 20 连接规模、长期连续稳定性、物理存储故障和本计划候选包均未在本轮执行，不因此宣称全项目发布通过。回放未安装时只记录 disabled，不伪造录像验收。

候选包准备项：第 5 轮核对最终源码与生产 JAR 摘要、无 GameTest／MCPFabric／调试辅助类、精确依赖、安装／升级／旧活动轮处置、备份／回退、全新演示目录与安装清单。本轮只构建验证，没有准备部署实例、发布候选包、推送或部署。


## 第 5 轮交接

- 状态：**开发与自动验证完成**；候选包和人工演示准备完成。真实客户端未验收，未发布或部署，P0～P4 与另外两版不放行。
- 会话及日期：01a148ba-89e8-7070-be75-73d7998ad8e0，2026-10-17。
- 启动基线：main，HEAD `741f58d5252c1f2f27303717202063ebfb034873`，工作树干净；仅根 AGENTS.md 适用。前四轮合并的 67 个最新源码及 29 份成功日志摘要一致，主能力和入口已复读，无前置整轮缺口。详见[基线](evidence/lobby-stabilization-20261017/baseline.json)。未发现已记录的人工实测反馈。
- 最终源码：348 个源码／资源／构建／脚本文件见[完整清单](evidence/lobby-stabilization-20261017/source-files.json)，聚合 SHA-256 `c19cb880be5e169ac6105dc157fd2da193697558986a32daed0a994efeaef417`。Git 基线和 `committed:false` 是验证时状态；本轮提交在文末另记。
- 全部结果、准确 argv、日志摘要、隔离路径与边界见[validation.json](evidence/lobby-stabilization-20261017/validation.json)。Java／GameTest／驱动／依赖在所有最终运行时检查间一致；最后只修 `scripts/test_p3_server_crash.py` 的过期数据夹具并重跑 Python，差异逐项核对仅此文件。完整入口原始失败保留在[runtime-matrix.json](evidence/lobby-stabilization-20261017/runtime-matrix.json)，最终 Python 成功另见[python-matrix.json](evidence/lobby-stabilization-20261017/python-matrix.json)，不把原始失败改写成一次性全绿。

### 修复与归属

1. 第 4 轮书屋的关闭／跨轮视图原先仍持有玩家和历史引用；现在绑定实际容器，关闭或连接／轮次失效后释放，迟到查询不能更新关闭页。第 1 轮菜单节流按原 250 ms 过期回收 UUID／action，保留重连限速。
2. 复现正常停止卡住时，时间截止值已经过去但原版 `runningTask` 仍为 true。`P1MainThreadExecutor` 让本项目 IO 只排主线程 `TickTask`，停服丢弃异步原生应用；`StoppedServerTaskMixin` 还保护原生停止回调的 owner 重入计数，保留父类任务执行／错误处理。没有跳过任务或更改区块排空条件。每个正常重启 JVM 实际并发执行 **16000 个停止回调＋2000 个 owner 任务**，回调全部完成且计数归零，父进程核对标记。新增三项原版 event-loop JUnit 含旧写法负对照。
3. 生产初始化改为 `dev.hardrush.HardRush`；`/hardrushp0`、P0 测试世界和清单仅在 development 源码集，由独立 debug-tools／GameTest 加载。正式路由和种子／天气通过接口保留。历史命名 `DisguiseProbe` 是实际怪物外观服务，继续留在生产。
4. mock 登录豁免和四套临时绑定／恢复方法从主产物移至 GameTest 专属辅助类／Mixin。真实身份门槛不放宽；夹具仍恢复原有绑定，不修改断言冒充通过。递归制品审计新增类与方法标记，禁止它们重新混入。
5. 原 Python 旧夹具只准备区块和档案，没有 Round 3 新增的大厅 COMPLETE 清单。补真实格式清单，保留原两个冻结文件及损坏拒绝断言，另断言清单精确摘要及缺失／PREPARED 状态拒绝；未削弱生产 retention 校验。

### 最终自动验证

所有服务端均新建隔离目录，专场串行、独占 JVM；没有操作日常数据库／存档。完整环境和 actual argv 由 JSON 保留，以下 `python3` 为当时记录的 Python 3.14 别名。

| 场景 | 命令 | 结果 | 日志／隔离路径 |
| --- | --- | --- | --- |
| 基础 JUnit／默认 GameTest | `scripts/p0-gradle.sh checkP3 -PhardrushP1=true --rerun-tasks --console=plain` | 371 JUnit（164＋94＋113）、110 默认 GameTest 通过 | [base.log](evidence/lobby-stabilization-20261017/checks/base.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/baseline-check/c95331bd-639e-406e-b824-8468d7e88749` |
| 20 连接三轮 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushStabilization=true --console=plain` | 1 个过滤结果通过 | [stabilization.log](evidence/lobby-stabilization-20261017/checks/stabilization.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/lobby-stabilization/c2d47194-e091-4c6e-aa88-a98b00134bca` |
| 完整无指令闭环 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushFullFlow=true --console=plain` | 1 个过滤结果通过 | [full-flow.log](evidence/lobby-stabilization-20261017/checks/full-flow.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/lobby-full-flow/3f8218de-5517-4338-b0f0-77639dfaa7d4` |
| 祭坛 SQLite 失败 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushFullFlow=true -PhardrushFullFlowFault=revival --console=plain` | 1 个过滤结果通过 | [revival-fault.log](evidence/lobby-stabilization-20261017/checks/revival-fault.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/lobby-full-flow/693e7da5-e468-4738-99f6-95591fc61ab5` |
| 定向追猎／适配器失败 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushPersonalHunt=true --console=plain` | 1 个过滤结果通过 | [personal.log](evidence/lobby-stabilization-20261017/checks/personal.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/personal-hunt/c53b4dba-0ede-4030-a874-169650f7ead9` |
| 追猎 SQLite 失败 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushPersonalHunt=true -PhardrushPersonalFault=storage --console=plain` | 1 个过滤结果通过 | [personal-storage.log](evidence/lobby-stabilization-20261017/checks/personal-storage.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/personal-hunt/d342e826-dfa8-45b7-85d5-2344ef7f0ff6` |
| 双观察者底座协议 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyFoundation=true --console=plain` | 1 个过滤结果通过 | [foundation.log](evidence/lobby-stabilization-20261017/checks/foundation.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/lobby-foundation/5aee1715-b0d8-4ccf-80bf-8336f29ea402` |
| 人类入场／重生 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushHumanEntry=true --console=plain` | 1 个过滤结果通过 | [human.log](evidence/lobby-stabilization-20261017/checks/human.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/lobby-human-entry/f7eca56d-6f7e-4411-968f-277ea9278138` |
| 大厅移动／重连 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushLobbyMovement=true --console=plain` | 1 个过滤结果通过 | [movement.log](evidence/lobby-stabilization-20261017/checks/movement.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/lobby-movement/8ab42c38-4b37-43f4-a252-ba054e1e0cec` |
| 20 次资格循环 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushP2Lobby=true --console=plain` | 1 个过滤结果通过 | [p2.log](evidence/lobby-stabilization-20261017/checks/p2.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/p2-lobby/fed2711e-5494-4f95-9dc5-d3fbd936ed4c` |
| 庆祝／临时物资 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushCelebration=true --console=plain` | 2 个过滤结果通过 | [celebration.log](evidence/lobby-stabilization-20261017/checks/celebration.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/celebration-regression/6ba7c15a-99f5-4d45-85d8-d5d8cac903fc` |
| 连续十轮 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushP1TenRounds=true --console=plain` | 2 个过滤结果通过 | [ten-rounds.log](evidence/lobby-stabilization-20261017/checks/ten-rounds.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/p1-regression/acfd3ab9-26ad-420a-9b14-a7ff8cfd1b63` |
| 存储重核／隔离处置 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushP3Recovery=true --console=plain` | 1 个过滤结果通过 | [recovery.log](evidence/lobby-stabilization-20261017/checks/recovery.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/p3-recovery/f6c6d9ae-c543-4096-b8ef-e1f80ae20843` |
| 空间／存储准入 | `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP3Storage=true --console=plain` | 1 个过滤结果通过 | [storage.log](evidence/lobby-stabilization-20261017/checks/storage.log)；`/Users/leifwebber/projects/minecraft/hardrush/.local/p3-storage/3276c97c-e298-476e-a3ea-a0c887104363` |
| 五阶段新 JVM 正常恢复 | `python3 scripts/p1-server-restart.py --phase COUNTDOWN --phase RUNNING --phase RESCUE --phase CELEBRATING --phase ROTATING` | 5 阶段、11 个不同 PID，exit 0／SERVER_STOPPED／SQLite／封套全部通过 | [restart.log](evidence/lobby-stabilization-20261017/checks/restart.log)；`详见专场结果` |
| PROFILE/PREPARED 强杀恢复 | `python3 scripts/p3-server-crash.py --scenario PROFILE --boundary PREPARED` | 父进程杀 1 个 writer，2 个新 JVM 恢复并保持隔离，通过 | [profile-crash.log](evidence/lobby-stabilization-20261017/checks/profile-crash.log)；`详见专场结果` |
| 31 项 Python 安全／制品检查 | `python3 -m unittest discover -s scripts -p 'test_*.py' -v` | 31/31 通过；仅测试夹具补 COMPLETE 清单后复跑 | [python.log](evidence/lobby-stabilization-20261017/checks/python.log)；`详见专场结果` |

110 默认结果包含未启用专项占位；13 个独立 GameTest 启动共 15 个过滤结果，其中庆祝／十轮各含另一个未启用方法，不能叠加当作额外实战。JUnit 报告见[汇总](evidence/lobby-stabilization-20261017/junit-summary.json)及 `evidence/lobby-stabilization-20261017/junit/`。

五阶段重启的 writer／recover PID：COUNTDOWN 1870／1939／1989，RUNNING 2049／2125，RESCUE 2171／2262，CELEBRATING 2319／2402，ROTATING 2500／2585。均为实际正常 stop／exit 0；原准备者／晚归／不安全恢复、13 秒活动状态、救场 50 秒及一次祭坛消费、庆祝 60 秒／returned、唯一下一轮和原生世界释放通过。[重启结果](evidence/lobby-stabilization-20261017/restart/result.json)保存精确快照、规则与档案封套。新规则旧活动轮隔离和旧编码／固定指纹仍由基础 JUnit／Session 回归验证，没有改历史成绩或指纹、恢复投票或手动维度。

PROFILE/PREPARED 的 PID 为 2701（父进程 SIGKILL）→2789→2840，两个新 JVM exit 0 且仍恢复隔离；原世界／档案／拥有权与 SQLite 核验通过。[强杀结果](evidence/lobby-stabilization-20261017/profile-crash/result.json)保留实际边界。本轮因夹具迁移选择这一组，没有机械重跑全部强杀矩阵；物理介质故障仍未测试。

规模专场是 **20 条实际原生嵌入连接**，8 轮个人页面／菜单循环与连续 3 个挑战（极限／普通／极限），另一个玩家离线跨 3 轮回归。41 个合成 profile 只作分页压力数据，不是额外参赛者。断线、关闭和换轮后检查视图、映射、模型 wire removal、提示与状态条的 owner 数量，旧实体包拒绝；无控制物、庆祝物资或旧物资串轮。普通死亡按钮、首龙、龙池和重新集结由真实原生入口触发；场景预置地形、低血量和测试时钟如实限定，不冒充满血自然生存、真实皮肤下载或 20 人画面／性能验收。

### 生产候选、安装与演示

- 生产 JAR：`platforms/mc263/build/libs/hardrush-0.1.0-p0+mc26.3.jar`，SHA-256 **`c01563b52d414ba6be5fd3be1c5c41053eb7fcc2339ab2a62b23508aab088dc6`**。递归审计含嵌入依赖，无本项目 GameTest、MCPFabric、debug-tools、管理／测试世界探针、mock 登录和临时配置辅助入口；官方 Fabric API 自带 GameTest API 是运行依赖，不是本项目测试模组。[审计](evidence/lobby-stabilization-20261017/jar-inspection.json)含精确元数据／依赖及逐嵌入 JAR 校验。
- 可安装归档：`/Users/leifwebber/projects/minecraft/hardrush/.local/demo/candidates/hardrush-lobby-26.3-c01563b52d41.tar.gz`，SHA-256 `0defd8abde722252fefa215bb980c0f36bf7c052ff38ba5e4283a42dab638e63`；目录制品 `/Users/leifwebber/projects/minecraft/hardrush/.local/demo/bundles/5b8ae0d1-74e7-48bd-ae77-9630435decd6`，内含启动器、两个顶层模组、许可证和 manifest，归档另附中文安装说明。均未上传、发布或部署。[候选清单](evidence/lobby-stabilization-20261017/candidate.json)区分构建时完整源摘要和最后单个 Python 测试夹具修正后的摘要，运行代码一致。
- 生产 smoke：普通／极限各新建存档，由 4 个独立 JVM 完成三维度就绪、正常停服、同轮重开，SQLite 完整性／外键、clean 标记及模式核对通过。[结果](evidence/lobby-stabilization-20261017/production-smoke-result.json)及[执行日志](evidence/lobby-stabilization-20261017/production-smoke.log)；无玩家，不证明游玩。
- 极限人工实例：`/Users/leifwebber/projects/minecraft/hardrush/.local/demo/instances/22a4936d-7adc-4db4-9b4d-dad8855d8b02`，准备后未启动。仓库根运行 `python3 scripts/demo.py run 22a4936d-7adc-4db4-9b4d-dad8855d8b02`，原版 26.3 连接 `127.0.0.1:25587`。
- 普通人工实例：`/Users/leifwebber/projects/minecraft/hardrush/.local/demo/instances/bbd7b735-a59e-4c53-a1fd-df568214581b`，准备后未启动。运行 `python3 scripts/demo.py run bbd7b735-a59e-4c53-a1fd-df568214581b`，连接 `127.0.0.1:25588`。
- 安装、精确依赖、升级、旧活动轮、完整一致性备份及回退见[候选包说明](lobby-candidate.md)。对应旧构建备份必须同时包含 SQLite／WAL、档案、世界及清单、配置和依赖；不能承诺只回退 JAR。没有在日常实例做升级／回退，也没有改旧成绩或规则指纹。
- `debugToolsJar` 编译通过；新增互斥组合被配置阶段正确拒绝，未启动混合服务端。日志分别见[开发包构建](evidence/lobby-stabilization-20261017/debug-tools-build.log)与[互斥拒绝](evidence/lobby-stabilization-20261017/mutex-rejection.log)。

### 失败保留与最终边界

[执行与失败索引](evidence/lobby-stabilization-20261017/progress-history.md)及 evidence 中 first／second／third／fourth、before-stop-fix／before-native-counter-fix／before-fixture-isolation 日志保留 API 编译、真实节流时间和登记顺序等失败。停服卡住的 PID 84732、92259 及其诊断、清理和被弃作正常恢复证据的结果分别保留在 `restart-first-failure/`、`restart-counter-failure/`；临时诊断 helper 的 Fabric 类加载错误没有算产品通过，第二次由原 runner 清理自己的 child，SIGKILL 不算正常停服。最终保护后的 11 个 JVM 与上述失败分开记录，没有通过降低期望、跳过回调或缩短排空放行。

最终没有未处理的错误目标、重复资格消费、串轮物资、错误传送或恢复放行问题。**仅本计划 26.3 开发与必需自动验证完成**；真实皮肤／渲染、准星、布局／音效、完整游玩、20 人 30 分钟性能、物理存储故障、录像与 AntiXray／Grim 完整集成、另外两版和整个 P0～P4 发布验收仍未放行。[第 5 轮人工路线](人工客户端测试/大厅无指令交互与定向追猎.md)和[庆祝清单](人工客户端测试/屠龙庆祝与提前返回大厅.md)全部未勾选。本轮交付后停止，不增加第六轮。

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

## 2026-10-17 第 3 轮提交

按用户“完成后提交”的明确授权，本次提交包含第 3 轮角色展位、UUID 皮肤庭院、冻结生命的个人定向追猎、受保护实体入口、搜索／补偿与故障隔离，以及所需夹具迁移、受影响恢复夹具修正、成功／失败证据和交接。它们共同组成此记录所属的 `feat(lobby)!` 提交，未拆成不能单独运行的中间状态。

提交整理只更新交接与证据，没有在通过后修改产品源码。28 个源码／测试／构建／脚本文件摘要和 9 项最终检查日志摘要已逐项核对；P3 收尾仅调整恢复夹具的共享大厅证据范围，产品与各原生专项代码保持已验证版本，最终基础及两种强杀边界另已重跑。验证 JSON 的基线 HEAD、dirty 与 committed=false 保留其验证时含义。人工项目全部待验，未启动第 4 轮、真实客户端、浏览器或日常实例，未推送或部署。

## 2026-10-17 第 4 轮提交

按用户“完成后提交”的明确授权，将剩余玩家入口、共享历史／展示、原生攻击入口的直接修复、所需专项与夹具迁移、全部成功／失败证据及交接共同整理为本记录所属的 `feat(lobby)` 提交。各部分共同完成第 4 轮玩家闭环，不拆成不能单独验收的中间状态。

提交整理只补文档和证据；最终 26 个源码／测试／构建摘要与通过时一致，所有最终日志摘要已核对。验证 JSON 中基线 HEAD、dirty 与 committed=false 是验证时的历史事实。没有推送、部署、真实客户端或日常实例操作，第 5 轮仍未开始。

## 2026-10-17 第 5 轮提交

按用户“完成后提交”的明确授权，按独立意图整理为三项：`f04868f`（`fix(runtime): 修复停服回调导致的重入计数竞态`）、`bf3098e`（`build(release)!: 将开发探针与夹具入口隔离出生产制品`），以及本记录所属的 `fix(lobby)`（页面回收、规模／救场集成、完整证据和候选交接）。制品隔离提交明确标记了开发命令及夹具 Java API 移出的兼容边界。

全部验证针对三项提交的最终组合源码；中间拆分提交没有单独复跑服务端。提交前逐项核对 348 个源码／资源／构建／脚本摘要及候选 JAR、归档摘要一致。证据中的 HEAD、dirty、committed=false 是验证／构建时的历史事实，保持原样；最后仅 Python 测试数据夹具改动的范围见本轮源码一致性说明。没有推送、发布、部署、真实客户端或日常数据库／存档操作。人工项目全部保持待验，五轮交付结束。
