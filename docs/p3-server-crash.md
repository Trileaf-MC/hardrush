# P3 第三批：整服世界、档案与 SQLite 联合强杀

日期：2026-10-15。范围为 26.3 无图形专用服务端，承接 [恢复管理入口](p3-recovery-controls.md)。新增可复验的整服 SIGKILL 矩阵，不再只将 Session、SQLite 和档案分别放在独立小进程中测试。结果和源码／制品指纹见 [E27 清单](evidence/p3-server-crash-20261015/manifest.json)。P3-01／02 和 P3-G 保持未完成。

后续 [第四批档案写盘窗口](p3-profile-crash.md) 已追加首次入轮的四个 journal 窗口；本页与 E27 保留第三批采集时的九场景结果，新版 runner 默认还包括 PROFILE 场景。

## 场景与边界

每个场景使用全新 UUID 目录、随机轮次和种子，启动实际 dedicated server。它持有实际三维度、原生玩家文件、档案 journal 和同一 SQLite 数据库。玩家通过嵌入连接走原生登录流程；不启动图形客户端或 computer use。

| 场景 | 强杀前的实际运行时入口 |
| --- | --- |
| AWARD 资格发放 | 两名人类正式加入／就绪，原生极限死亡转怪物；冷却后经正式安全搜索出场，原生僵尸近战击杀另一人类 |
| CONSUME 资格消费 | 完成上述击杀、等待资格与档案落实；已获资格者原生败退回大厅，再执行正式 `hardrush revive` 命令 |
| TERMINAL 首龙终局 | 原生第一只末影龙生成，玩家普通最后一击，沿用真实终局捕获与结算路径 |

每个场景分别在以下三处被父进程 SIGKILL：

1. SNAPSHOT：真实 SQLite 事务已更新 `round_snapshots`，尚未提交。
2. INDEX：同事务已更新目标 `participant_records`，或已插入 `history_rounds`，尚未提交。
3. COMMIT：SQLite 已提交，Session 的主线程回调尚未应用。

事务内通过仅存在于测试模组的 TEMP trigger／SQLite function 暂停。小页缓存和事务内 256 KiB 测试 BLOB 强制产生未提交 WAL 帧；父进程记录 WAL 增长并强杀自己刚启动的 JVM。测试不以自行正常退出、超时清理、SIGTERM 或另一个服务端的 PID 作为通过证据。COMMIT 使用测试反射包装回调执行器；不向生产代码加入 halt、阻塞触发器或新的恢复开关。

## 恢复检查

每个被杀存档连续由两个不同 PID 的新 dedicated server JVM 打开。每次均先恢复为 RECOVERY_REQUIRED，再用原 UUID 走原生重连：

- UUID／种子／规则／快照版本、身份、资格、生命序号及已提交终局与强杀时的确定资料一致。事务内失败回到原快照，COMMIT 保留新快照；资格与原生效果不会在重连时重放。
- 全员留在永久大厅并保持锁定，拒绝加入、消费资格和继续比赛；原轮三维度不重新加载或模拟，竞速时间不增加。
- SQLite 完整性、外键、事件摘要、原始快照字节、历史及参与记录摘要保持一致；未提交测试 BLOB 行回滚为零。
- 三维度文件、清单、档案 journal／封存快照逐文件 SHA-256 保持一致，档案 envelope 的长度和校验和有效。26.3 的原版 `players/data` 是原生重连／保存的工作文件，不当作不可变归档比较。
- 恢复进程正常停止也不重写原始脏检查点。第三个 JVM 再次按该检查点隔离，不会因为先前正常退出就自动恢复比赛或改变可信终局。

正常 RUNNING 停服／新 JVM 恢复另行回归，检查共享的原生登录、世界和测试时钟夹具仍沿用正常恢复行为。

## 自动结果

9组强杀／双重开、27个独立专用服务端JVM全部通过；342项JUnit、101项常规必需无图形GameTest（含5个未启用专场门禁）、12项Python测试通过。另以2个独立JVM完成正常RUNNING停服／恢复。最终runner对9个保留存档再次只读核验快照摘要、WAL测试行回滚、记录及档案envelope／文件哈希，全部通过；发布JAR不含这两类重启／强杀夹具。

初版夹具错误要求把内存恢复隔离写回原始检查点，另有入场准备未等待已持久化的保护离区权限，相关尝试未记为通过。前者改为保留原始脏检查点，后者在夹具准备中显式走正式离区持久化请求；失败／中止日志保留在E27。没有因此放宽生产保护或改写恢复逻辑。

## 复验

```sh
python3 scripts/p3-server-crash.py
python3 scripts/p3-server-crash.py --scenario CONSUME --boundary COMMIT
python3 -m unittest discover -s scripts -p 'test_p*_server_*.py' -v
scripts/p0-gradle.sh checkP3 :platforms:mc263:runGameTest -PhardrushP1=true --console=plain
python3 scripts/p1-server-restart.py --phase RUNNING
```

runner 从现有 `.local/server-p0/eula.txt` 复制已接受的 EULA，不代替用户接受。服务端只绑定 loopback 随机端口，启用白名单、关闭 RCON／query，使用 `.local/p3-server-crash/<本次UUID>/<场景>-<边界>`。失败和通过的存档、日志及结果均保留，不接触日常运行实例，也不自动清理失败数据。强杀命令只由本轮 runner 对其直接子进程发送。

## 验证边界与剩余任务

本批覆盖资格发放／消费／终局三类操作的三个 SQLite 边界。它没有覆盖同一整服在档案 PREPARED／目标悬停／APPLIED／VERIFIED 的每个写盘窗口，也未支持不确定档案自动修复或继续原活动轮。档案各窗口此前只有 E25 独立进程证据；不能因为本批同时存在真实档案就宣称这些窗口也已命中。

场景先保存实际世界与原生玩家的确定基线，然后保持服务端运行并强杀目标事务；不保证强杀时尚未保存的世界变化恢复，也不模拟断电／介质缓存丢失。为聚焦持久化，使用同步测试时钟、平坦人工战斗场地、显式持久化保护离区请求、嵌入网络、预加载区块，以及低血量／禁 AI／跳过竞技场加载的首龙。它不替代原版客户端画面、自然地形出场、满血生存龙战或磁盘真实 ENOSPC／只读介质测试。

没有新增归档／删除、ServerReplay、AntiXray 或 Grim 集成。后续仍须完成不确定档案的可审计处置、跨轮整服档案边界强杀、文件系统故障及 P3-03～11。人工项目见 [整服强杀人工检查](人工客户端测试/P3整服强杀恢复.md)。本批与 E28 一同检查并提交，未推送或发布；E27 清单保留采集时的源码与制品指纹。
