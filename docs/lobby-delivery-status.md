# HardRush 26.3 无指令大厅交接状态

初始化日期：2026-10-16。当前阶段：第 1 轮开发与自动验证完成，其他轮次未开始。对应[五轮计划](lobby-delivery-plan.md)、[产品设计](lobby-interaction-design.md)和[启动词索引](lobby-delivery-prompts/README.md)。

## 总体状态

| 项目 | 当前状态 |
| --- | --- |
| 交付范围 | 26.3；26.1.2／26.2 正式适配另排 |
| 当前开发轮次 | 第 1 轮开发与自动验证完成；第 2 轮未开始 |
| 新交互开发 | 交互底座、独立人偶投影、规则边界完成；完整营地及个人追猎等后轮业务尚未接通 |
| 本计划的自动产品验证 | 当前源码 354 项 JUnit、106 项默认 GameTest，以及独立底座协议 1/1、大厅移动 1/1 通过；默认占位成功不代替专项 |
| 真实客户端人工验收 | 未执行，待实现后按人工清单开展 |
| Git 提交 推送 部署 | 第 1 轮已按 2026-10-17 用户要求分意图提交；未推送或部署。验证基线仍记录为 3fc1f835，提交记录见文末 |
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
| 2 营地与人类入场 | 未开始 | 第 1 轮自动门槛已通过，待本轮会话复核源码与证据 | 无 | 按第 2 轮启动词实施营地与权威入场 |
| 3 皮肤庭院与定向追猎 | 未开始 | 第 1 轮已通过，第 2 轮未执行 | 无 | 等待第 2 轮通过并交接 |
| 4 全流程无指令接通 | 未开始 | 第 1 轮已通过，第 2～3 轮未执行 | 无 | 等待第 3 轮通过并交接 |
| 5 稳定化与候选包 | 未开始 | 第 1～4 轮尚未执行 | 无 | 等待全部玩家业务实现 |

后轮未开始不等于技术失败。第 1 轮执行发现的失败与修复见下文；本轮必需自动门槛现已通过，人工画面与后轮业务仍未验收。

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

- 状态：未开始。
- 执行会话及日期：尚无。
- 前轮复核结论与本轮基线：尚无。
- 营地建造 设施与落点：尚未实现。
- 集结权威操作 中途门与恢复接口：尚未实现。
- 自动验证命令 新 JVM 结果与证据：未执行，无证据。
- 人工验收：未执行。
- 留给下一轮：完成第 2 轮后填写角色展位／庭院如何接入，以及第 3 轮使用的布局与请求入口。

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
