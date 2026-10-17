# HardRush

HardRush 是面向原版客户端的 Fabric 多人速通挑战模组。玩家在同一世界争夺末影龙最后一击；极限模式下，死亡玩家可以成为怪物追猎活人，并通过击杀获得再次作为人类参赛的资格。

本项目采用 [MIT License](LICENSE)。第三方组件保留各自许可证，详见 [第三方组件说明](THIRD-PARTY.md)。

屠龙后最多庆祝三分钟：末地在线玩家自动装备临时鞘翅并领取 16 色烟花，其他维度玩家先回大厅；龙池出口直接返回独立大厅，当前在线玩家全员返回后提前准备下一轮。原物资封存，重连保持庆祝余量与已返回状态。详见[人工检查](docs/人工客户端测试/屠龙庆祝与提前返回大厅.md)。

2026-10-16 已确认[大厅与全流程无指令交互设计](docs/lobby-interaction-design.md)：站入集结区准备、离开取消；怪物右键活人皮肤模型直接追猎，取消投票和手动选维度。设计、[26.3 五轮计划](docs/lobby-delivery-plan.md)、[会话启动词](docs/lobby-delivery-prompts/README.md)与[人工验收草案](docs/人工客户端测试/大厅无指令交互与定向追猎.md)已准备，五轮开发尚未开始，实际进展见[交接状态](docs/lobby-delivery-status.md)。

**26.3 MVP 演示入口：** `python3 scripts/demo.py prepare` 创建独立极限实例，再用 `python3 scripts/demo.py run <实例UUID>` 启动；原版客户端连接 `127.0.0.1:25587`。普通模式、局域网双人、正常重开及自动检查见 [演示说明](docs/mvp-demo.md)，试玩项目见 [人工清单](docs/人工客户端测试/MVP演示.md)。这是生产 JAR 的开发演示入口，各阶段正式验收仍待完成。

2026-10-16 起，普通／极限正式世界取消出生保护、离区后禁止返回的限制，以及人工石台、台阶、护栏和出口通道，直接使用自然地形安全站位；大厅保护和原版末地到达平台保留。旧世界不自动拆除已经生成的建筑。下文旧批次的出生保护记录作为历史保留，当前规则及待人工检查项见 [自然出生点检查](docs/人工客户端测试/正式世界自然出生点.md)。

2026 年 10 月 13 日已形成需求与技术方案评审基线；10 月 14 日用户明确调整外观要求：其他玩家必须看到完整怪物外观，本人允许保留普通玩家外观。目标发布版本为 Minecraft Java Edition 26.1.2、26.2、26.3，各版本分别构建和验收，原版客户端可完整游玩。

- [已确认的产品决策](docs/decisions.md)
- [产品需求与赛制](docs/requirements.md)
- [大厅布局与全流程无指令交互设计](docs/lobby-interaction-design.md)
- [26.3 无指令大厅五轮交付计划](docs/lobby-delivery-plan.md)
- [五轮开发交接状态](docs/lobby-delivery-status.md)
- [各轮完整 Codex 启动提示词](docs/lobby-delivery-prompts/README.md)
- [大厅无指令交互与定向追猎人工验收草案](docs/人工客户端测试/大厅无指令交互与定向追猎.md)
- [怪物数值与操作](docs/monster-balance.md)
- [技术设计](docs/technical-design.md)
- [版本与依赖核查](docs/compatibility.md)
- [验收与开发顺序](docs/acceptance.md)
- [P0 至 P4 开发任务清单与依赖](docs/implementation-plan.md)
- [P0 原型验证与运行方法](docs/p0-validation.md)
- [P0b 三版本基础依赖、SQLite 与原版容器核查](docs/p0b-dependency-evidence.md)
- [MCPFabric、GameTest、JDWP 与 spark 调试环境](docs/debug-tools.md)
- [真实怪物角色与技能原型](docs/p0-monster-roles.md)
- [P1 第一批规则与持久化基础](docs/p1-foundations.md)
- [P1 基础人工客户端检查](docs/人工客户端测试/P1基础与持久化.md)
- [P1 管理确认、审计与成绩修订](docs/p1-admin-controls.md)
- [P1 管理人工客户端检查](docs/人工客户端测试/P1管理命令与成绩修订.md)
- [P1 同服十轮与新 JVM 正常恢复记录](docs/p1-ten-round-regression.md)
- [P1 十轮与正常恢复人工检查](docs/人工客户端测试/P1十轮与正常恢复.md)
- [三分钟屠龙庆祝、鞘翅烟花与提前返回大厅人工检查](docs/人工客户端测试/屠龙庆祝与提前返回大厅.md)
- [P2 正式身份与出生保护](docs/p2-identity-protection.md)
- [P2 身份与保护人工检查](docs/人工客户端测试/P2身份转换与出生保护.md)
- [P3 第一批崩溃隔离、档案恢复与存储故障](docs/p3-crash-storage.md)
- [P3 崩溃隔离与存储故障人工检查](docs/人工客户端测试/P3崩溃隔离与存储故障.md)
- [P3 第二批存储重核与恢复处置](docs/p3-recovery-controls.md)
- [P3 恢复决策与存储重试人工检查](docs/人工客户端测试/P3恢复决策与存储重试.md)
- [第三方组件](THIRD-PARTY.md)

玩法及首轮数值已经确认。开发首先验证旁人完整怪物外观与本人正常操作、动态三维度与末影龙流程，以及三版本的回放和防作弊组合，再推进完整功能。

本目录已实现 26.3 的 P0 原型：旁人怪物／本人 Steve 外观、单轮持久三维度、真实怪物角色与技能及管理员探针命令。另已接通独立 P1 普通模式、首龙终局与轮换、统计榜单和历史书；P2 第一批已接通正式普通／极限身份和出生／大厅保护；怪物大厅／追猎／安全出场、正式角色技能入口／持久冷却及攻击白名单已接通；资格复生、救场与完整身份记录已接通；真实客户端回归和完整发布验收仍未完成。P0 验收结果与已知阻塞见验证记录，不能把构建成功视为功能通过。

后续推进以 [开发任务清单](docs/implementation-plan.md) 为入口：按任务编号维护已完成、未完成和阻塞状态，列出依赖、验收条件与已有证据。阶段顺序为 P0 → P0b → P1 → P2 → P3 → P4；原 B01 已因用户调整需求而撤销，新的旁人怪物／本人 Steve 外观已实现，剩余 P0 验收仍待完成。2026-10-14 已提前完成三版基础依赖、SQLite 与 SGui 的独立服务端探针，不代表最终多版本玩法适配通过。

当前 **P0 未通过**：旧原型的自身替身遮挡与选取失败证据继续保留，但不再要求本人显示怪物。新实现仅向旁人发送怪物外观，本人保留原版玩家模型、相机和操作，并优先使用固定 Steve 皮肤；用户已通过外观操作测试并接受轻微尺寸差异。真实角色、自动装备和环境规则已通过 9 项 JUnit 与 35 项服务端 GameTest，合并人工验收由用户暂缓，最终图形生命周期回归仍待补齐。

2026-10-14～15 按用户指示提前推进 P1：已接通三维度预算准备、登录／跨轮档案、普通加入／就绪／死亡计时、首龙唯一终局、庆祝与安全轮换、统计／规则分榜、TAB／原版历史书，以及 OP 分权、确认审计和带书版的成绩修订。第六批修复在线档案悬停的旧世界玩家登记残留，216 项 JUnit、常规 64 项必需 GameTest（含 1 项未启用十轮 gate）和独立十轮运行时整链通过；新增七阶段 Session／SQLite 新 JVM 正常恢复。当前轮不可变，只有下轮规则可改。26.3 入口默认关闭，独立实例用 `-Dhardrush.p1.enabled=true` 启用，首次新库可加 `-Dhardrush.p1.initialMode=normal`；P2 第一批现已允许正式极限开赛并接通死亡转怪物大厅，后续 P2 已接通出场／资格复生／救场，完整客户端验收仍待完成。自动验证入口为 `scripts/p0-gradle.sh checkP1 -PhardrushP1=true --console=plain`，不启动图形客户端。同服真实三维度／档案十轮无图形整链已完成，复验另加 `:platforms:mc263:runGameTest -PhardrushP1TenRounds=true`，每次使用新的隔离存档。第七批另完成七阶段整服世界＋档案新进程正常恢复，修复倒计时恢复空窗与停服回调线程边界；最终 219 项 JUnit、64 项必需无图形 GameTest 和 14 个独立服务端 JVM 通过，见 [第七批记录](docs/p1-server-restart-regression.md)。真实客户端十轮及各阶段停服仍未验，P0／P0b／P1 阶段未通过。

整服世界＋档案正常恢复的独立入口为 `python3 scripts/p1-server-restart.py`，可用 `--phase COUNTDOWN` 只运行一个场景；默认覆盖 PREPARING、WAITING、COUNTDOWN、RUNNING、SETTLING、CELEBRATING、ROTATING。脚本每次在 `.local/p1-server-restart/<随机UUID>` 建立隔离存档，各场景先启动 writer 专用服务端 JVM，完整正常停止并退出后，再用同一存档启动不同 PID 的 reader。仅复制 `.local/server-p0/eula.txt` 中用户已经接受的 EULA；逐步日志、fixture 结果和 SQLite／档案校验留在运行目录，失败资料保留。2026-10-15 七场景最终统一复跑已通过，结果、源码／制品指纹及完整日志见 [本批证据](docs/evidence/p1-server-restart-20261015/manifest.json)。

该入口运行普通无图形 dedicated server，测试辅助代码只在 GameTest 测试制品中加载，不进入发布 JAR。嵌入式网络玩家、同步测试时钟、直接原生传送和低血量固定首龙用于覆盖服务器生命周期与 IO；不代表原版客户端画面、走门操作或满血生存通过。真实客户端十轮与各阶段人工 stop 继续按 [人工清单](docs/人工客户端测试/P1十轮与正常恢复.md) 验收，强杀／dirty 故障恢复留给 P3，不能据此勾选 P1-G。

新外观层已移除 `selfview` 命令、本人替身、伪装隐身、私有队伍与相机实验，允许持物／装备；三种外观绑定唯一真实玩家，并在同一服务端会话内保留死亡重生、重连与跨维度选择。本人 Steve 皮肤通过原版网络刷新，解除恢复原皮肤。实现、验证和边界见 [新外观记录](docs/p0-observer-appearance.md)，[旧研究](docs/p0-self-view-evidence.md)作为历史保留。

构建需要 Java 25，运行 `scripts/p0-gradle.sh checkP0`。原型 JAR 位于 `platforms/mc263/build/libs/hardrush-0.1.0-p0+mc26.3.jar`；仅供隔离测试，依赖 Fabric Loader 0.19.5 与 Fabric API 0.162.0+26.3。

26.3 开发调试入口：`python3 scripts/debug-tools.py start --side client` 启动独立服务端和带 MCPFabric/spark 的开发客户端；`python3 scripts/debug-tools.py verify` 复验游戏测试、MCP 控制、源码断点和实际性能采样。工具只存在于开发实例，完整操作与证据见调试文档。

P0b 无图形界面基础依赖复验：`scripts/p0-gradle.sh -p probes/p0b checkP0bDependencies --console=plain`，逐版运行 SQLite／SGui／Fantasy 加载探针。该独立工程不访问 P0 存档，完整结果与边界见 [P0b 记录](docs/p0b-dependency-evidence.md)。

2026-10-15 P2 第一批已提前推进 P2-01／P2-02：极限原版掉落后提交怪物身份、原生重生和 UUID 重连；普通床／锚与新生命保护；默认 32 格圆柱全高度出生保护、全区大厅保护及持久离区限制。验证使用 `scripts/p0-gradle.sh checkP2 -PhardrushP1=true --console=plain`；正式极限整链另运行 `scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushP2Identity=true --console=plain`，使用新隔离存档。旧 P0／P1 夹具在测试制品中明确隔离保护配置，新保护夹具与专用正式整链分别验证保护挂钩及实际绑定；生产没有测试豁免。当前极限死者留在隔离大厅，完整玩法与客户端验收仍未通过，细节和证据见 [P2 开发记录](docs/p2-identity-protection.md)。

2026-10-15 P2 第三批继续接通 P2-07／08：正式角色身体读取本轮配置，技能右键／`/hardrush skill`／菜单先提交 SQLite 再生效，冷却跨返厅／换角／败退／重连与正常重启保留；原生模型、召唤物和箭矢使用冻结来源及统一命中前白名单。大厅／追猎／安全出场基线见 [第二批记录](docs/p2-lobby-deployment.md)，本批实现、证据和限制见 [正式角色与攻击记录](docs/p2-monster-combat.md)，客户端测试见 [人工清单](docs/人工客户端测试/P2正式角色技能与攻击白名单.md)。本批源码与记录一同提交，资格复生／救场与阶段验收仍待后续。

2026-10-15 P2 第四批继续完成 26.3 开发范围的 P2-09～16，并开放大厅复生按钮／命令：技能持久归属与原版行为专项、原生死亡批次资格发放、空装复生、60 秒救场和完整统计／重复请求防护。实现、自动结果及边界见 [复生与救场记录](docs/p2-revival-rescue.md)，真实客户端完整循环请按 [人工测试清单](docs/人工客户端测试/P2复生救场与完整闭环.md) 验证。P2-17／P2-G 仍未通过，未启动图形客户端。

2026-10-15 按用户指示提前推进 P3：第一批补崩溃隔离、档案恢复与存储故障防护；第二批接通 OP 3 的 `admin recheck-storage` 和 OP 4 的 `admin close-recovery`，均需具体轮次确认与事务审计。数据库重核成功仍留厅隔离；结束恢复轮保留已提交资格／原始终局，安全释放后才创建下轮，已关闭的故障世界只读核验并保留文件。自动入口为 `scripts/p0-gradle.sh checkP3 -PhardrushP1=true --console=plain`，独立恢复整链另加 `:platforms:mc263:runGameTest -PhardrushP3Recovery=true`。尚不支持继续原活动轮次，P3-01／02／G 未完成；当前操作流程和边界见 [P3 第二批记录](docs/p3-recovery-controls.md)。

2026-10-15 P3 第三批新增整服联合强杀复验：`python3 scripts/p3-server-crash.py`，覆盖资格发放／消费／首龙终局在快照、索引和提交后回调前三个边界的9组实际SIGKILL，每组由两个新JVM重连核验，27个服务端JVM全部通过；世界／档案保留、资格与原终局不重放。实现、证据及剩余边界见 [整服强杀记录](docs/p3-server-crash.md)，客户端检查见 [人工清单](docs/人工客户端测试/P3整服强杀恢复.md)。P3-01／02／G仍未完成。

2026-10-15 P3 第四批追加首次入轮档案 PREPARED／SUSPENDED／APPLIED／VERIFIED 四个整服写盘窗口，外部 JDI 命中实际 journal 方法返回后由父进程 SIGKILL；四组及双新 JVM 重开均通过，旧封存／原生物资／目标空档案保持隔离。复验：`python3 scripts/p3-server-crash.py --scenario PROFILE`；新版默认 runner 同时包含原九组 SQLite 场景。详见 [档案强杀记录](docs/p3-profile-crash.md)。跨轮整服档案恢复、不确定资料修复和继续原活动轮仍待后续。
