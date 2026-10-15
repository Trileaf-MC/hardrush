# P3 第四批：整服档案 journal 写盘窗口强杀

日期：2026-10-15。承接 [第三批 SQLite 整服强杀](p3-server-crash.md)，补充首次入轮档案转换的四个实际写盘窗口。范围为 26.3 无图形专用服务端；P3-01／02／G 保持未完成。最终结果、PID、源码和制品指纹见 [E28 清单](evidence/p3-profile-crash-20261015/manifest.json)，客户端检查仍交由 [人工清单](人工客户端测试/P3整服强杀恢复.md)。

## 实际入口与断点

每个场景均创建独立存档，先准备实际三维度和确定的 SQLite 检查点。测试通过显式 unmanaged fixture 范围完成一次原生登录、写入13颗钻石／17枚金锭末影箱／100经验，再原生退出保存，建立首次由 HardRush 管理前的玩家文件。该准备范围只存在于测试制品；随后再次登录沿用正式的登录隔离和档案 worker。

外部 JDI 辅助进程连接父 runner 刚启动的服务端。JDWP 仅监听随机 loopback 端口，读取测试模组中的 PID 核对目标；方法返回事件还核对 worker 线程名和玩家 UUID。匹配时冻结全部线程，原子写出命中记录，父进程才向自己的该子进程发送 SIGKILL。

| 窗口 | 被暂停的实际方法返回 | 强杀后必须存在的资料 |
| --- | --- | --- |
| PREPARED | `ProfileJournal.prepare` | 旧档案已经封存，journal 为 PREPARED，没有目标快照 |
| SUSPENDED | `ProfileJournal.suspend` | journal 仍为 PREPARED，目标空档案已经完整落盘 |
| APPLIED | `ProfileJournal.advance` 返回 APPLIED | 同一完整目标快照及 APPLIED journal |
| VERIFIED | `ProfileJournal.advance` 返回 VERIFIED | 同一完整目标快照及 VERIFIED journal，主线程尚未放行 |

JDI 暂停发生在现有原子替换完成后的方法返回，调用方没有机会继续下一个写入或回调。不在正式档案代码中加入 halt、磁盘观察器、测试回调或故障开关；辅助 Java 源文件由 runner 单独启动，不进入发布 JAR。

## 双重开检查

每个被杀存档连续由两个不同 PID 的新 dedicated server JVM 打开，并走同 UUID 的原生登录。检查：

- 原始脏 SQLite 检查点、轮次／种子／规则／版本、事件与记录摘要不变；没有创造终局或推进时间。恢复进程正常退出也不清除脏检查点。
- 两名玩家仍在永久大厅锁定；不能加入、消费资格或进入旧维度。旧维度不重新模拟，原生旧物品、末影箱与经验继续隔离，不重复清空或放行。
- PREPARED 缺目标时，journal 的恢复 API 明确拒绝。后三个窗口能读回同一完整、空背包／空末影箱／零经验的目标，且轮次、代数和 suspension ID 匹配。
- 两次重开均不改写 journal、旧封存、目标快照或旧世界，逐文件 SHA-256 一致；目标悬停标记保持相同。独立 Python 核验还解码 journal 的实际阶段，并比较原生 `players/data` 文件摘要。

恢复运行时按既有策略保持 RECOVERY_REQUIRED，不会自动执行档案修复或继续比赛。读回可恢复目标不等于已通过完整管理恢复决策。本批没有增加这样的权限或行为。

## 复验

```sh
python3 scripts/p3-server-crash.py --scenario PROFILE
python3 scripts/p3-server-crash.py --scenario PROFILE --boundary APPLIED
python3 -m unittest discover -s scripts -p 'test_p*_server_*.py' -v
scripts/p0-gradle.sh checkP3 :platforms:mc263:runGameTest -PhardrushP1=true --console=plain
```

新版 runner 默认执行原九个 SQLite 场景及四个 PROFILE 窗口；可用 `--scenario AWARD --scenario CONSUME --scenario TERMINAL` 单独复验旧矩阵。E27 的证据记录其采集时的九场景版本，E28 记录本批最终版本。

四个 PROFILE 场景、12个独立服务端 JVM 的最终强杀／双重开矩阵通过，另抽查 AWARD／COMMIT 的3个 JVM 和正常 RUNNING 停服／恢复的2个 JVM。342项 JUnit、101项常规必需无图形 GameTest（含5个未启用专场门禁）及17项 Python 检查通过。原 E27 的117个归档证据文件已重新核对 SHA-256，九个保留存档也由最终 runner 只读复核。

runner 沿用已经接受的 EULA、独立 UUID 目录、仅 loopback 的服务端和严格子进程所有权检查。所有失败、超时或中断现场保留；失败清理的终止信号不计为成功强杀。

首轮夹具错误写入旧 `playerdata` 路径，命中窗口后的恢复断言拒绝空封存，没有记为通过。直接构造离线玩家基线的第二次尝试发生服务端 tick 停滞，已中断并保留日志；随后改用真实登录／原生退出保存来建立基线，并在转换前用 `PlayerList.loadPlayerData` 回读核验。生产恢复／档案逻辑没有因此放宽。修正后矩阵及最终独立核验通过，初始失败与中断证据随 E28 保留。

## 验证边界

本批验证首次入轮的真实 journal 流程，未覆盖跨轮转换、原子替换前的 pending 文件写入中途、断电或介质缓存丢失。跨轮新进程、目标快照不确定状态的可审计修复、继续原活动轮、文件系统 ENOSPC／只读介质以及 P3-03～11 仍须继续推进。自动测试没有运行真实客户端或 computer use，不替代原版客户端的画面和交互验收。
