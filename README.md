# HardRush

HardRush 是面向原版客户端的 Fabric 多人速通挑战模组。玩家在同一世界争夺末影龙最后一击；极限模式下，死亡玩家可以成为怪物追猎活人，并通过击杀获得再次作为人类参赛的资格。

2026 年 10 月 13 日已形成需求与技术方案评审基线；10 月 14 日用户明确调整外观要求：其他玩家必须看到完整怪物外观，本人允许保留普通玩家外观。目标发布版本为 Minecraft Java Edition 26.1.2、26.2、26.3，各版本分别构建和验收，原版客户端可完整游玩。

- [已确认的产品决策](docs/decisions.md)
- [产品需求与赛制](docs/requirements.md)
- [怪物数值与操作](docs/monster-balance.md)
- [技术设计](docs/technical-design.md)
- [版本与依赖核查](docs/compatibility.md)
- [验收与开发顺序](docs/acceptance.md)
- [P0 至 P4 开发任务清单与依赖](docs/implementation-plan.md)
- [P0 原型验证与运行方法](docs/p0-validation.md)
- [P0b 三版本基础依赖、SQLite 与原版容器核查](docs/p0b-dependency-evidence.md)
- [MCPFabric、GameTest、JDWP 与 spark 调试环境](docs/debug-tools.md)
- [真实怪物角色与技能原型](docs/p0-monster-roles.md)
- [第三方组件](THIRD-PARTY.md)

玩法及首轮数值已经确认。开发首先验证旁人完整怪物外观与本人正常操作、动态三维度与末影龙流程，以及三版本的回放和防作弊组合，再推进完整功能。

本目录已实现 26.3 的 P0 原型：旁人怪物／本人 Steve 外观、单轮持久三维度、真实怪物角色与技能及管理员探针命令。它尚不是可开服比赛的 HardRush，正式赛制、轮换、死亡转角色／大厅复生、排行榜与历史书仍未实现。P0 验收结果与已知阻塞见验证记录，不能把构建成功视为功能通过。

后续推进以 [开发任务清单](docs/implementation-plan.md) 为入口：按任务编号维护已完成、未完成和阻塞状态，列出依赖、验收条件与已有证据。阶段顺序为 P0 → P0b → P1 → P2 → P3 → P4；原 B01 已因用户调整需求而撤销，新的旁人怪物／本人 Steve 外观已实现，剩余 P0 验收仍待完成。2026-10-14 已提前完成三版基础依赖、SQLite 与 SGui 的独立服务端探针，不代表最终多版本玩法适配通过。

当前 **P0 未通过**：旧原型的自身替身遮挡与选取失败证据继续保留，但不再要求本人显示怪物。新实现仅向旁人发送怪物外观，本人保留原版玩家模型、相机和操作，并优先使用固定 Steve 皮肤；用户已通过外观操作测试并接受轻微尺寸差异。真实角色、自动装备和环境规则已通过 9 项 JUnit 与 35 项服务端 GameTest，合并人工验收由用户暂缓，最终图形生命周期回归仍待补齐。

新外观层已移除 `selfview` 命令、本人替身、伪装隐身、私有队伍与相机实验，允许持物／装备；三种外观绑定唯一真实玩家，并在同一服务端会话内保留死亡重生、重连与跨维度选择。本人 Steve 皮肤通过原版网络刷新，解除恢复原皮肤。实现、验证和边界见 [新外观记录](docs/p0-observer-appearance.md)，[旧研究](docs/p0-self-view-evidence.md)作为历史保留。

构建需要 Java 25，运行 `scripts/p0-gradle.sh checkP0`。原型 JAR 位于 `platforms/mc263/build/libs/hardrush-0.1.0-p0+mc26.3.jar`；仅供隔离测试，依赖 Fabric Loader 0.19.5 与 Fabric API 0.162.0+26.3。

26.3 开发调试入口：`python3 scripts/debug-tools.py start --side client` 启动独立服务端和带 MCPFabric/spark 的开发客户端；`python3 scripts/debug-tools.py verify` 复验游戏测试、MCP 控制、源码断点和实际性能采样。工具只存在于开发实例，完整操作与证据见调试文档。

P0b 无图形界面基础依赖复验：`scripts/p0-gradle.sh -p probes/p0b checkP0bDependencies --console=plain`，逐版运行 SQLite／SGui／Fantasy 加载探针。该独立工程不访问 P0 存档，完整结果与边界见 [P0b 记录](docs/p0b-dependency-evidence.md)。
