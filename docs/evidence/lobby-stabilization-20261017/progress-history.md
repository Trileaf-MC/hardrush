# 第 5 轮执行进度与失败索引

日期：2026-10-17。最终结论见 `../../lobby-delivery-status.md` 第 5 轮；本页保留过程归属，原始成功／失败日志和隔离目录不清理。

1. main／741f58d5252c1f2f27303717202063ebfb034873，工作树干净。前四轮最新 67 个源码与 29 份成功日志摘要一致，业务主流程已交付；人工反馈没有新结果。修改代码前先将第 5 轮与总表标为进行中。
2. 新规模夹具首次编译遇 26.3 的实体交互构造参数、实体移除访问器与 ChunkPos 构造差异，按实际 API 修正。`stabilization-first.log`、`stabilization-third.log` 保留编译失败。
3. `stabilization-second.log` 的短暂停顿没有可靠证明真实 250 ms 已过，改为真实单调时间门槛，没有缩短生产节流。`stabilization-fourth.log` 在断线事务未提交时申请下轮模式，被正确拒绝；夹具改为先排空。仅对本轮 child PID 80428 发 SIGTERM 清理，不计正常恢复通过。
4. 20 条嵌入连接、8 次分页／菜单循环与 3 轮实战状态随后通过。书屋关闭视图与菜单过期节流改为及时回收；旧页面、迟到 IO、断线／轮换后的引用和物资边界保留强断言。
5. 生产审计发现 `/hardrushp0`、WorldProbe、P0 RoundRuntimeLevel 和 WorldManifest 在 main，移到 development 源码集。正式 bootstrap、世界路由及种子／天气接口独立出来；实际怪物外观引擎 DisguiseProbe 保留。随后深层审计再移出 mock 登录豁免及四套临时绑定辅助方法，测试专用辅助类／Mixin 接管原夹具作用域，不放宽生产登录门槛。对应中间源变更导致旧冻结矩阵主动失效，分别保存在 matrix-before-* 目录与 before-fixture-isolation.log；不冒充最终源码通过。
6. 第一处 RUNNING 停服卡住为 PID 84732，线程栈停在原版 stopServer／waitUntilNextTick。临时 agent 诊断 helper 遇 Fabric classloader 隔离，造成该诊断进程异常退出，结果弃作正常停止证据。保留 running-stop-thread-dump.txt、restart-first-failure/，没有操作日常服务器。
7. 单独 RUNNING 四组重试正常退出，但没有据重试抹去首次失败。源码显示内层线程判断之前，stopped 的 server.execute 已在 worker 进入共享重入计数，新增 P1MainThreadExecutor 与三项原版 event-loop JUnit；负对照和 16000 个并发停止提交通过。
8. 进一步在 COUNTDOWN recover-again 的 PID 92259 复现：只读诊断打印的两个时间截止值都早于当前时间，runningTask 仍为 true。仅本项目调度修复不足，原生回调也会触发。保留 restart-counter-failure/；向本 runner 的 Python 发中断，原 runner 只对自己的 child 做失败清理，记录 SIGKILL／exit -9，不计正常恢复通过。
9. StoppedServerTaskMixin 让 off-owner 回调保持原版父类执行与错误处理，但不更新 owner 的重入计数。每个正常 JVM 新增 16000 个原生停止回调与 2000 个 owner 任务并发检查，断言全部执行、计数零，父进程也核验标记。未改区块排空条件。RUNNING＋COUNTDOWN 五 JVM 探针通过，见 restart-counter-probe/；最终五阶段 11 JVM 另行重跑通过。
10. 最终 Java／GameTest／正常重启／PROFILE/PREPARED 都通过，最后 Python 检查暴露第 3 轮 retention 更新后，旧测试夹具缺大厅 COMPLETE 清单。保留原两个冻结文件和损坏拒绝断言，补实际格式清单与精确摘要，另拒绝缺失／PREPARED 状态；只改这一 Python 测试文件，31 项重跑通过。生产／GameTest／驱动／依赖字节逐项一致，原始整入口的最后失败保留在 runtime-matrix.json；最终聚合结果为 validation.json。
11. 所有真实客户端、皮肤／选取／音效、完整游玩、20 人性能和物理介质项目保持未验收。没有推送、发布、部署或清理失败存档；候选包、两个未启动人工实例与安装／备份回退说明已准备。
