# P1 第六批：同服十轮无图形整链与新 JVM 正常恢复

更新：2026-10-15。按用户指示继续 P1-17，范围为 Minecraft 26.3 普通模式开发回归。已修复在线悬停残留旧维度玩家登记，通过同服真实三维度／档案／运行时十轮最终复跑，以及七阶段 Session／SQLite 新 JVM 正常停止恢复矩阵。最终常规检查为 216 项 JUnit 和 64 项必需无图形 GameTest（含 1 项未启用十轮 gate），独立十轮整链为另 1 项必需 GameTest。没有运行真实客户端、截图、computer use 或满血生存游玩。P0、P0b、P1-G 仍未通过；本页不将 P1-17 或 P1-04／05／09／10／11 勾为完成。

源码／制品指纹、命令、日志和最终测试报告统一归档在 [本批证据目录](evidence/p1-ten-round-regression-20261015/)。验证采集时的工作树与后续提交状态由清单分别记录，不以开发回归表示推送、部署或发布。

## 修复实际登录隔离的旧世界残留

十轮整链第一轮在 `requireEvacuated` 关闭门槛失败：玩家已经回大厅，但旧 `ServerLevel` 的原生玩家列表仍保留同一玩家。原因是 `P1PlayerProfiles.onLogin` 对已连接玩家先 `setServerLevel(lobby)`，再执行原版传送；原版传送看到字段已经是目标世界，按同世界移动处理，未从原世界解除实体／玩家登记。

修正后，已连接玩家保留真实源世界，通过原版传送进入大厅，让原版完成旧世界注销与新世界登记。尚未注册连接的登录前分支仍直接设置大厅世界／位置；登录前捕获原 NBT、原维度和坐标的逻辑保持。回归通过真实运行时轮换证明玩家撤离后旧世界可以完成保存／关闭，不通过放宽关闭门槛绕过残留。

## 同一运行时的十轮覆盖

`P1TenRoundGameTest` 使用启用的 `P1LobbyRuntime`、真实 `P1LobbySession`／SQLite worker、`P1PlayerProfiles` 档案 IO 和三维度 `P1RoundWorldBundle`，不手工调用世界释放接缝创建下轮。玩家通过原生 `PrepareSpawnTask` 登录，嵌入式连接接收网络包并发送传送／加载 ACK；实际 Fabric 生命周期、join／ready 命令、入场、死亡／重生、攻击、终局与轮换均经过运行时。

最终独立复跑已完成十个唯一首龙结果，实际轮换到第 11 轮 WAITING；保存十条对应规则榜成绩，注销三十个旧维度并逐个完成 native IO 关闭。逐轮已领主册的持久 ACK、第十轮主册在第十一轮发放、床／锚绑定清空、参赛名单／普通死亡重生统计和新 RUNNING 阶段的旧轮回调拒绝均通过。运行后原生正常停服保留第 11 轮 WAITING／clean 标记。完整输出见 [最终日志](evidence/p1-ten-round-regression-20261015/ten-rounds-final.log)，只读数据库汇总见 [results.json](evidence/p1-ten-round-regression-20261015/results.json)。

| 覆盖 | 检查内容 |
| --- | --- |
| 真实三维度与身份 | 每轮三维度注册对象／所有权／种子与 SQLite 一致；新 UUID／新种子，旧维度不重新出现在服务器注册表 |
| 普通入口与计时 | 原生命令 join／ready、30 秒规则倒计时、实际档案放行后人类入场；逐轮有效时间分别为 1～10 秒，终局后保持冻结 |
| 同轮重连 | 第 3 轮经原生断连／登录恢复原下界位置和背包／末影箱；档案与在线身份核验后才恢复比赛 |
| 离线跨三轮 | 第二 UUID 在第 1 轮保存物资／末影箱／效果和旧位置后下线，缺席第 2～4 轮，第 5 轮登录；新轮清空旧物资／末影箱／经验／效果／进度／出生绑定，缺席轮名单不伪造参加者 |
| 普通死亡／重生 | 第 2 轮施加原生死亡伤害，客户端命令包请求原版重生；替换实体等待新的生命序号提交，死亡／重生次数不串轮 |
| 首龙与记录 | 每轮原生首龙由实际 `Player.attack(dragon.head)` 致死，运行时因果捕获、唯一终局及 SQLite 提交；进度／首次末地按本轮保存，迟到旧轮进度回调在新轮拒绝 |
| TAB 与成书 | 从实际原版 TAB 包核对尾刀者；下一轮背包出现真实历史成书，第 10 轮书在第 11 轮领取，增强复跑核对库存保存后同版 ACK |
| 释放与保留 | 自动庆祝／轮换先封存档案、退回长期大厅，再等待旧三维度实际注销和 native IO 关闭；检查 bundle 的 `held`／`inFlight`／`requested` 及静态所有权引用清空，旧清单仍在 |

这是无图形服务端整链：测试直接原生跨维度传送，不代表真实玩家走门；TAB 包和书物品不代表字体、翻页或背包画面通过。实际首龙为 **0.1 血量、固定站位、禁 AI 和 `skipArenaLoadedCheck` 夹具**，保留普通玩家攻击和因果钩子，不代表满血生存击龙。

加速时间仅在测试制品内同步替换 Session 的 `MonotonicClock` 与战斗捕获的单调时钟采样，使致死捕获和计时边界使用同一来源；生产时钟与基础攻击伤害不变。600 秒庆祝按规则快进而非真实等待十分钟。旧维度／任务／所有权引用断言不等同于完整堆泄漏、长时负载或实际录制器验收；未安装录制器按 disabled 分支，不声称产生录像。

## 七阶段的新 JVM Session／SQLite 矩阵

`P1ProcessRestartTest` 对 PREPARING、WAITING、COUNTDOWN、RUNNING、SETTLING、CELEBRATING、ROTATING 分别启动 writer JVM 和恢复 reader JVM。writer 正常 `prepareStop()`／`close()` 并退出，reader 使用同一真实 SQLite 文件启动；校验两个不同 PID、明确 PASS 和退出码 0，不是同进程关闭再打开。

矩阵检查原轮 UUID／序号／种子／规则、有效挑战时间、阶段剩余量、参与者及生命序号、终局、进度、上一轮历史和已领书状态；当轮普通规则与下轮极限配置分开保存。测试在捕获停止边界后增加虚拟排空时间，在新进程使用不同单调时钟原点，证明停止等待和进程间隔不会算入成绩／倒计时。恢复时先核验适配，再允许在线身份重新计时；已结算轮恢复只能生成一次下轮，旧名单和末地统计不带入。

**此矩阵只启动 Session／SQLite 子进程，没有启动整台 Minecraft 服务器、加载真实三维度或执行档案 IO。** 世界 READY、档案放行、终局来源与释放使用测试接缝，倒计时／庆祝规则缩短；不证明正常整服停止后的世界／档案／登录整链。强制终止、dirty 恢复与故障仲裁仍属于 P3，子进程测试超时清理也不算强杀恢复通过。

## 运行入口与最终结果

常规检查：

```bash
scripts/p0-gradle.sh checkP1 :platforms:mc263:runGameTest -PhardrushP1=true --console=plain
```

十轮独立运行，使用与其他会修改共享大厅的 GameTest 分离的过滤入口：

```bash
scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushP1TenRounds=true --console=plain
```

十轮开关强制要求 P1 启用并使用首次普通规则，每次创建 `.local/p1-regression/<随机UUID>` 独立存档；只复制开发实例已经接受的 `eula.txt`，不会代用户接受 EULA。常规 suite 中该测试未启用时立即返回成功，这一 gate 不计作十轮已执行；只有独立开关运行的完整测试才提供十轮证据。测试 mixin、嵌入连接与加速时钟属于 GameTest 制品，不进入发布 JAR。

| 验证 | 当前结果 |
| --- | --- |
| 七阶段新 JVM Session／SQLite 正常恢复 | 7 项全部通过；14 个独立子进程，PID／退出 0 留在 [JUnit 报告](evidence/p1-ten-round-regression-20261015/platforms-mc263-TEST-dev.hardrush.p1.P1ProcessRestartTest.xml) |
| 最终常规 `checkP1` | 构建通过，80 core＋68 persistence＋68 platform＝216 项 JUnit；failure／error／skipped 均为 0；[最终日志](evidence/p1-ten-round-regression-20261015/check-p1-final.log) |
| 最终常规服务端 GameTest | 64 项必需测试通过，24.35 秒；其中 63 项实际常规场景，包括新增原生玩家列表迁移回归，另 1 项为未启用十轮 gate |
| 加强后的独立十轮复跑 | 独立 1 项必需整链通过，测试 32.03 秒、Gradle 41 秒；存档为 `.local/p1-regression/b99177cf-7d0c-40f5-bad5-fba09ee170ae/world`；十结果／12 条参与记录／11 条已领主册（10 份 A 主册＋跨轮登录 B 的最新册），SQLite integrity_check＝ok；[源码／制品清单](evidence/p1-ten-round-regression-20261015/manifest.json) |

以上实际测试结果和 SHA-256 清单补充 P1-17 自动进展。真实客户端十轮、完整新进程整服世界＋档案恢复、P2 极限／救场、真实回放和多版本完整回归继续待验；不能据此勾选 P1-17、P1-G 或其仍未完成的入口／计时／档案／轮换／正常恢复任务。

## 人工检查与后续

用户手动测试见 [P1 十轮与正常恢复](人工客户端测试/P1十轮与正常恢复.md)，沿用独立实例、默认规则真实等待、现有管理员预览／本人 120 秒确认及失败保留资料流程。该清单覆盖同服十轮至第 11 轮 READY、同轮重连、离线跨三轮、普通死亡、背包／末影箱／坐标／效果／进度隔离、首龙、TAB／书页、各阶段 `stop` 和旧世界保留／释放证据。

正常整服重启需要另行在目标阶段保存、真正退出服务端进程，再以同一数据库／世界／档案启动核验；Session 新 JVM 和本页同服十轮不能互相拼接成这个尚未运行的整链结论。救场／极限的恢复矩阵等 P2 接通后补齐；P3 再覆盖强杀、写入／磁盘／回放失败、归档及完整防护组合。失败保留 SQLite 主库与 WAL、所有权清单、档案、旧世界、日志和已有录像，不静默删档或手改标记凑通过。
