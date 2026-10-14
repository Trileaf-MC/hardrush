# P1 第三批：首龙终局、庆祝与自动轮换

更新：2026-10-14。按用户指示继续提前开发 P1，P0／P0b／P1-G 仍未验收。本批实现范围限定为 Minecraft 26.3 普通模式与开发适配；139 项 JUnit 和 52 项必需无图形服务端 GameTest 通过，源码／制品指纹及完整日志见[本批证据](evidence/p1-terminal-rotation-20261014/manifest.json)。核心／存储提交为 `077e78a`，26.3 接入与本记录一同提交；未推送或发布，未运行真实客户端／computer use。证据清单的 `uncommitted` 描述验证采集时的工作树状态。

## 首龙与唯一结果

`P1LobbyRuntime.verifiedFirstDragon` 先核对致死事件属于当前就绪 P1 bundle、本轮 END 内仍有实际原生末影龙、事件 victim UUID 等于原生 fight 的 dragon UUID，且该 fight 尚未完成首次击杀。非致死、普通实体冒充、别轮龙和后续复召均不能建立新的首龙终局。P0 探针不作为 P1 比赛或成绩来源。

`DragonDeathArbiter` 使用攻击创建时封存的来源身份和生命序号判断尾刀。真实已参赛人类创建的箭／爆炸，即使主人随后死亡、下线或进入下一生命，仍按创建时人类来源处理；不会用死亡当下的角色撤销合法攻击。怪物、旧轮、不明或未参赛来源没有可信胜者，实际首龙死亡仍保存通关结果和待核验原因，不猜最近攻击者。

首次捕获不可逆致死候选后，Session 立即关闭新竞技行为；不等数据库回调才冻结。已经进入同一 `batchId` 的原生伤害链可以完成：人类近战、箭命中、床／锚、TNT／水晶等最外层攻击／爆炸退出时触发 `batchClosed`，同批普通人类死亡先入队，然后排队唯一 FINISH。合法人类同批死亡不会令通关失效。P2 的极限怪物转换、资格奖励、救场与团灭仲裁仍未接通，本批不宣称这些场景验收通过。

## 原子保存与计时

SQLite schema 3 的 `round_terminals` 保存每轮唯一终局，并保留原始 SETTLING 快照、实际致死战斗事件和首龙 UUID。`commitTerminal` 在一个 CAS 事务中提交：

1. 实际致死证据及摘要；
2. 原始 SETTLING 轮次快照和 FINISH 事件；
3. 每轮唯一的终局行及其与证据的关联。

事务先核验事件标签、旧版本、轮次／种子／规则及尾刀裁决。任一步失败全部回滚；同内容重放幂等，事件 ID 碰撞或竞争终局不能留下部分证据。后续庆祝与停服检查点不能改胜者、停止后的挑战时间或原轮身份。读取终局重新核对摘要和关联；schema 2→3 的 DDL／版本一起提交，失败保留旧 schema 和数据。历史已有结果保留，不补造原本不存在的首龙证据。

首龙 `observedNanoTime` 是挑战停表与庆祝起点。默认成功庆祝为 600 秒服务器运行时间；数据库排队／提交延迟消耗剩余庆祝，不加到成绩、不在公布回调时重新起算。若提交延迟已经耗尽庆祝，提交后的状态可以直接进入 ROTATING。未知来源的实际通关也保留待核验结果，不把它转换成失败或另起一次竞技。

成功提交才公布胜者／待核验与已停止成绩。SETTLING／CELEBRATING 的原参与者可以在受控范围继续停留；同轮重连恢复本轮悬停档案，关闭旧容器后放行庆祝返回。新攻击、竞技交互与物资转移被封住，包含不经网络包的原生物品／经验碰撞拾取；成绩保持不变。实际客户端输入、容器画面与重连表现须按[人工检查](人工客户端测试/P1终局与自动轮换.md)由用户确认。

## 自动轮换的释放门槛

普通／开发入口当前采用以下顺序；没有庆祝期间预开下一轮世界：

| 顺序 | 当前实现 |
| --- | --- |
| 1 | 庆祝剩余量耗尽，旧轮进入 ROTATING |
| 2 | 所有在线玩家回长期大厅，退出载具／容器；受管玩家先封存旧轮档案，等旧代数 VERIFIED |
| 3 | 未安装回放明确写日志 disabled；已安装 `ReplayPort` 则等待同轮／同操作标签的 VERIFIED `stopAndFlush` |
| 4 | `saveAndClose()` 重读所有权清单并核验三维度 ID／种子／持久属性／实际注册对象／存储路径，拒绝仍有玩家或不安全路径 |
| 5 | 主线程保存并释放 Fantasy handles，持续 tick 等实际注销，再关闭 native `ServerLevel`／region IO，完成 future |
| 6 | 旧轮全部释放后，异步持久化下一轮新 ID、新种子、递增顺序号与待生效规则；提交成功才替换运行快照 |
| 7 | 新轮按原准备／档案门槛进入 WAITING；旧轮回调不能影响新轮 |

`close()` 只是启动同一异步流程，不能作为已关闭证据；调用方必须继续对旧 bundle 调 `tick()` 并等待 `saveAndClose()` 成功。CLOSING／CLOSE_FAILED 期间仍持有 roundId，不能靠同 ID 重开取消卸载。关闭拒绝根目录／大厅／未登记或错误活动世界／符号链接；关闭后原清单、区块、种子和建筑保留，同 ID 安全重开不重造地形。

当前没有真实录制器、自动世界归档或删除。回放不可信、档案失败、世界关闭失败均停止自动推进并保留资料；关闭 API 支持修复后重试，但面向管理员的完整故障恢复、录像重试／明确跳过和审计入口留给 P3。`retry-world` 只重试原轮准备失败，不能跳过终局、档案、回放或崩溃门槛。

## 正常停止与恢复

SERVER_STOPPING 首先 `prepareStop()` 捕获单调停止时刻、关闭新竞技行为并补收已捕获而尚未闭合的终局批次。随后封存在线受管档案、等待已安装回放的独立保存屏障、保存世界、排空档案 worker，最后排空已经受理的状态／证据／终局事务与数据库 worker。前置保存或核验失败先进入适配故障，不写正常 clean marker。档案 worker 在后台记录 IO 失败，主线程即使尚未执行失败回调，close 也会拒绝正常停服成功；该边界用真实档案目录写入失败回归。停止排空等待不增加挑战时间或庆祝消耗。

停服中仍注册的世界交给后续原版关闭循环释放；若轮换已注销部分旧世界，`shutdown()` 关闭剩余 native IO。正常重开保留轮次身份、结果和阶段剩余量，先验证世界和档案；停服墙钟间隔不算成绩或庆祝。脏标记继续要求 `RECOVERY_REQUIRED`，没有实现完整强制终止恢复或用世界重试命令清除不确定性。

## 开发入口与当前边界

P1 默认关闭。用户自行准备独立 Fabric 26.3 实例、安装本批 HardRush JAR 与对应 Fabric API 后，首次普通模式新库可从该实例目录使用 Java 25 启动：

```bash
java -Dhardrush.p1.enabled=true -Dhardrush.p1.initialMode=normal --enable-native-access=ALL-UNNAMED -jar fabric-server-launch.jar nogui
```

启动 JAR 文件名是示例，按该实例实际文件替换。仓库 `scripts/p0-gradle.sh :platforms:mc263:runServer -PhardrushP1=true -PhardrushP1InitialMode=normal --console=plain` 只使用固定 `.local/server-p0` 开发目录，不自动创建隔离实例。

`initialMode` 只用于没有已持久化规则的首次新库；已有当前轮次和下轮规则以数据库为准。省略时默认极限，目前极限准备／加入后仍拒绝就绪开赛，因为 P2 闭环未完成。`/hardrush config next-mode normal|hardcore` 只影响下轮；不能用它改变当轮规则或缩短当轮 600 秒。

本批没有实现统计／进度去重、分榜、TAB、历史书、极限角色／救场闭环、三版完整适配或连续 10 轮验收。下一批优先 P1-12～15。P1-04／05／09／10／11 保留未完成并记录已交付的普通／开发部分；P1-07 按本批实现与自动验证范围完成，阶段验收仍独立。

## 自动验证与待人工项目

完整命令 `scripts/p0-gradle.sh checkP1 :platforms:mc263:runGameTest -PhardrushP1=true --console=plain` 通过：139 项 JUnit（core 49、persistence 42、platform 48），failure／error／skipped 均为 0；52 项必需无图形服务端 GameTest 全通过，测试运行 16.76 秒，Gradle BUILD SUCCESSFUL 27 秒。统一证据见[本批清单](evidence/p1-terminal-rotation-20261014/manifest.json)，执行日志原始路径为 `.local/p1-terminal-final-check.log`。

新增测试覆盖范围包括纯核心创建身份尾刀、同批死亡和旧轮拒绝；真实 SQLite 原子事务／冲突／回滚／迁移／摘要／重开；Session 提交延迟／600 秒剩余量／唯一换轮／正常停止；回放 disabled 与 VERIFIED 屏障／失败／过期回调；无图形原生首龙致死→Session→SQLite 链、庆祝交互／重连，以及三维度真实关闭后同 ID／种子／建筑恢复。

原生龙链使用随机独立轮次、受控玩家、首龙生命值 0.1 及 `skipArenaLoadedCheck`，由原生 `Player.attack` 触发，不改基础攻击伤害；这些是服务端夹具，不代表满血原版生存战斗或双真实客户端整轮通过。假时钟推动 600 秒属于规则时间验证；真实用户检查应实际等待十分钟，不能靠更改当轮配置或数据库伪造。连续 10 轮、已安装真实回放可播放、完整客户端与 P2／P3 场景均待后续验收。
