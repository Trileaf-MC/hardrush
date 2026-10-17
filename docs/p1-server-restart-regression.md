# P1 第七批：整服世界与档案的新 JVM 正常恢复

日期：2026-10-15。范围为 Minecraft 26.3 普通模式的无图形专用服务端。继续 P1-11／P1-17，新增七阶段真实 Minecraft 服务端 writer／reader JVM 整链，修复重启倒计时丢失剩余量和停服回调越过主线程边界的问题。最终结果以本页验证表和 [证据目录](evidence/p1-server-restart-20261015/) 为准。

本批未启动真实客户端、computer use 或浏览器。P0、P0b、P1-G 和 P1-17 的完整人工验收仍未完成；极限身份与救场在 P2，强杀、dirty、磁盘／回放故障及归档在 P3。倒计时核心提交为 `4df678d`，其余本批源码与记录一同提交；尚未推送、部署或发布。验证采集时的基线与实际源码／制品 SHA-256 见证据清单，提交状态文案的后续更新单独记录。

## 修复正常重启的倒计时空窗

原实现恢复时正确把旧连接全部标记为离线；但三维度 READY 后的常规 Session tick 会把零在线就绪者当作最后一人主动离线，取消倒计时，清空已保存的剩余量和 ready。此前 Session／SQLite 新 JVM 测试在世界接缝放行后立即登录，没有覆盖真实世界先准备、玩家稍后回来的窗口。

现在仅对 clean `COUNTDOWN` 恢复暂挂：世界 READY 可以持久化 dirty 检查点，原 ready UUID 尚未完成档案上线时保留阶段、余量和 ready；其他 UUID 上线不能替代这个门槛。原 ready 玩家档案与世界都已核验后从保存余量继续。暂挂期间再次正常停服仍保存原余量；已经恢复运行后，显式取消就绪或最后 ready 玩家断线仍取消倒计时，新的 ready 仍使用完整默认 30 秒。状态文字说明等待原因，暂挂期间加入／就绪请求返回具体等待提示，管理员入口继续沿用现有分权、确认和审计。

新增三项实际 SQLite Session 回归，覆盖多次检查点、重复正常 stop、无关 UUID、档案先于世界 READY、恢复后的取消和后续人类入场计时门槛；原有七阶段 Session 新 JVM 矩阵也加强 COUNTDOWN 的 READY 后 5 秒空窗。这些是独立的规则／存储证据，整服场景另外实际执行。

## 停止后的回调仍由主线程收口

致死捕获后立即正常 halt 的日志还暴露了原先的回调边界：Minecraft 的服务端循环退出时先设置 stopped，再进入 stopServer；此时 `server.execute()` 可能同步执行提交方的任务。数据库 worker 的终局回调因而可能直接应用引擎／玩家／TAB 副作用，档案 worker 也可能在 close 之前应用原生玩家转换。运行时与默认档案回调入口现在都在实际执行处核对服务端主线程，停服时不在 worker 应用这些回调。已受理 future 仍由主线程上的 `session.close()` 排空并原子保存，档案 journal 的封存 IO 由 `profiles.close()` 排空；不丢弃已受理写入或提前标 clean，未完成转换留给下进程按原 journal 恢复。

整服夹具包装真实 `committedListener` 和档案回调，任何非服务端线程的提交／档案应用都会明确失败；SETTLING 场景保持实际致死后立即 halt，验证这条生产停止路径。

## 整服入口和通过判定

```bash
python3 scripts/p1-server-restart.py
```

可追加 `--phase COUNTDOWN` 运行单阶段，或重复 `--phase` 选择多个阶段。脚本只复制 `.local/server-p0/eula.txt` 中用户已明确接受的 EULA，不自行接受条款；每次创建 `.local/p1-server-restart/<随机UUID>/<阶段>/` 独立实例。网络绑定 loopback、随机端口、空白名单、离线开发身份，不接入日常服务器或既有比赛存档。

Gradle 的 `writeP1RestartLaunchSpec` 一次编译并生成 Java 启动配置；Python 随后直接启动 JVM，使用 GameTest source set 中的测试辅助模组，但入口是普通 dedicated server，没有启用 GameTestServer runner。每阶段 writer 完成目标流程后调用与控制台 stop 相同的 `MinecraftServer.halt(false)`，由实际 Fabric／原版正常停止监听器封存档案、保存世界并排空 SQLite；测试不提前关闭 P1 服务，也不在停服前断开玩家。完整退出后才以同一目录启动不同 PID 的 reader。

必须同时满足以下条件才记通过：JVM exit 0；实际 `SERVER_STOPPED` 之后 fixture 明确 PASS；操作／阶段／PID 对应；writer 与 reader PID 不同；恢复源 UUID／序号与 writer 保存值对应；真实三维度内容和玩家状态断言通过；SQLite `integrity_check` 为 ok，最新检查点字节／摘要／clean 标记正确；manifest 与档案 envelope SHA-256 校验通过；日志没有原版保存、关闭或解锁失败。原版可能只记录关闭异常仍 exit 0，所以退出码不能单独作为成功依据。

失败保留日志、数据库／WAL、世界清单、三维度和档案。超时或中断只清理本 runner 的子进程，并写 FAIL；这类终止不算正常恢复或强杀恢复通过。完整运行目录不提交进仓库，证据目录归档日志、结果、源码／制品校验和测试报告，清单记录原始存档位置。

## 七阶段实际覆盖

| 阶段 | writer 与 reader 的实际检查 |
| --- | --- |
| PREPARING | 未 READY 的原生部分世界写入可辨方块，正常退出后同 UUID／种子／规则继续准备，原方块保留，不另建替代轮 |
| WAITING | 真实三维度、两 UUID 原生登录／档案和已加入名单；新进程先在永久大厅隔离，再完成原轮核验；不误开赛 |
| COUNTDOWN | 默认 30 秒已经过 7 秒，writer 独立断言保存 23 秒；reader 世界 READY 后无人登录再快进 5 秒，余量仍为 23 秒，原 ready UUID 档案上线后继续 |
| RUNNING | 两名原生就绪玩家实际入场，A 普通死亡／原生重生至生命序号 2；两人在不同维度留下背包、末影箱、经验、效果、出生绑定和进度；writer 独立断言 13 秒挑战及个人时长；新进程恢复原状态后再加 5 秒有效时间 |
| SETTLING | 实际原生首龙经玩家攻击产生致死捕获，尚未等另一个服务端 tick 就 halt；生产停止路径排空唯一终局；新进程恢复可信胜者、原始因果、冻结 13 秒成绩和原生致死龙 UUID／生命；未完成的原版死亡动画继续至首杀标记 |
| CELEBRATING | 同一实际首龙流程，默认 600 秒庆祝已经过 120 秒；writer 独立断言剩余 480 秒，新进程保留胜者、历史、物资和余量 |
| ROTATING | 在真实旧轮进入轮换时正常停止；新进程先核验旧三维度内容，再由生产释放路径实际关闭旧维度；旧 bundle CLOSED、三个 native IO 已关闭，旧键注销且文件保留；只创建一个新 UUID／下一序号，新档案物资隔离，上一轮真成书的来源／版／卷和持久 ACK 正确 |

除 PREPARING 的部分世界外，其余场景在三维度写不同数量的真实箱子物品及可辨建筑，并核对独立 SavedData 时钟与天气从磁盘恢复。活动／终局场景还读取真实 SQLite 参赛记录，核对两个参赛者、原生进度、死亡／重生及首次末地；终局场景同时核验不可变 terminal、可信历史投影和仅一条成绩，不能只凭最新 Session 快照通过。

## 夹具和证据边界

这是无图形服务端验证。玩家通过真实 `PrepareSpawnTask`／玩家列表与生命周期登录，网络由 `EmbeddedChannel` 模拟并回复原生传送／加载 ACK；没有真实客户端输入或画面。位置通过原生传送设置，不证明玩家走门；物资／进度和世界对照建筑由测试准备，不属于真实生存流程。

规则仍是默认普通模式的 30 秒倒计时和 600 秒庆祝，但 Session 与原生攻击观察共用同步加速单调时钟；世界时钟在对照测试中暂停。首龙夹具为 0.1 HP、固定位置、禁 AI 和 `skipArenaLoadedCheck`，保留实际玩家攻击及因果钩子。该结果不代表满血生存击龙、真实等待十分钟、性能或录像验收。

整服夹具在 stop 排空期间保持测试时钟不变，证明的是已知累计值／余量及新 JVM 恢复；排空耗时排除另由已有 Session 停服边界与阻塞 SQLite 自动测试覆盖，不能单独用本夹具声称已经验证真实 IO 等待计时。成功矩阵也不覆盖世界清单异步失败、region IO 关闭失败、脏库、回放保存失败或强制终止的恢复策略。

测试 main 入口、反射时钟、嵌入网络与攻击观察接缝仅在 GameTest source set，发布 JAR 检查另确认不含该测试代码。生产改动集中在 Session 倒计时恢复门槛及 Runtime／档案主线程回调保护，没有增加图形测试或生产测试豁免。

## 最终验证

最终统一复跑全部通过。七阶段使用同一最终源码与测试制品，原始存档位于 `.local/p1-server-restart/922b973d-78f7-4e5b-b4a7-3c65e16b5556/`，完整结果见 [results.json](evidence/p1-server-restart-20261015/results.json)。

| 验证 | 结果 |
| --- | --- |
| 七阶段真实专用服务端 writer／reader JVM | 7／7 通过，14 个不同 PID／exit 0；逐阶段存储校验和完整日志已归档 |
| `checkP1` JUnit | 80 core＋68 persistence＋71 platform＝219 项通过，failure／error／skipped 均为 0 |
| 常规必需无图形 GameTest | 64 项通过，其中 63 项常规场景及 1 项未启用十轮 gate；不重复声称本批执行十轮 |
| Python 语法、源码 diff 与生产 JAR 测试代码排除 | 全部通过；生产 JAR 不含 GameTest／debug 辅助代码 |

真实客户端十轮、TAB／书页、满血战斗、原版输入及各阶段人工 stop 继续按 [P1 十轮与正常恢复](人工客户端测试/P1十轮与正常恢复.md) 执行。本批只补齐 P1-11／P1-17 的普通模式自动进展，不勾选 P1-G，也不把 P2 救场或 P3 故障矩阵当作完成。


## 2026-10-17 无指令大厅稳定化补验

当前 runner 默认增加第八阶段 RESCUE，原七阶段历史证据保持原意。第 5 轮选择 COUNTDOWN／RUNNING／RESCUE／CELEBRATING 复跑，准确日志及新 JVM 结果见[大厅交接](lobby-delivery-status.md#第-5-轮交接)。RESCUE 使用极限模式，经原生死亡、庭院模型出场和原生怪物击杀产生资格，正常停服保存 60 减 10 的 50 秒；新 JVM 复核同轮、外出怪物回营地与原资格，通过祭坛原生确认包及重复请求验证仅一条空装新生命恢复挑战。地形、低血量人类与测试时钟是夹具；不是图形游玩或物理故障证据。COUNTDOWN 仍用三个 JVM 检查晚归原准备者与不安全恢复。


同轮稳定化还修正了此前线程保护的位置：原先在 `server.execute` 的任务内部检查当前线程，但 stopped 时原版 `execute` 已先在 IO 调用方进入非线程安全的 `ReentrantBlockableEventLoop.doRunTask`，即使空任务也会竞争共享重入计数。`P1MainThreadExecutor` 现在从 worker 直接排 `TickTask`，停止后不再进入该执行器；owner 线程仍可处理 bootstrap／close 排空。档案、Session、书册、庆祝和释放回调共用，原版区块排空条件保持不变。实际原版 event loop 的负对照和 16000 次并发停止提交回归，以及正常 JVM 复验见第 5 轮；首次卡住、诊断失败及中间重试均保留。


实际复现进一步确认：原生停止回调也会在 worker 调用 `MinecraftServer.execute`，因此仅修复本项目提交点仍不足。`StoppedServerTaskMixin` 对 off-owner 的服务器任务保留原版父类任务执行／错误处理，避开 owner 的共享重入计数；不跳过任务、不改变区块排空谓词。每个正常重启 JVM 在真实 stopped 状态并发执行 16000 次原生回调和 2000 次 owner 任务，要求回调全部执行、共享计数为零，父进程核对这些标记。该诊断和最终验证仍只作用于 runner 自己创建的新实例。
