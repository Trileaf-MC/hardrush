# HardRush

HardRush 是面向原版客户端的 Fabric 多人速通挑战模组。玩家在同一世界争夺末影龙最后一击；极限模式下，死亡玩家可以成为怪物追猎活人，并通过击杀获得再次作为人类参赛的资格。

2026 年 10 月 13 日已形成需求与技术方案评审基线。目标发布版本为 Minecraft Java Edition 26.1.2、26.2、26.3，各版本分别构建和验收。原版客户端可完整游玩，且玩家自身第三人称必须显示怪物，是不可降级的要求。

- [已确认的产品决策](docs/decisions.md)
- [产品需求与赛制](docs/requirements.md)
- [怪物数值与操作](docs/monster-balance.md)
- [技术设计](docs/technical-design.md)
- [版本与依赖核查](docs/compatibility.md)
- [验收与开发顺序](docs/acceptance.md)
- [P0 原型验证与运行方法](docs/p0-validation.md)
- [第三方组件](THIRD-PARTY.md)

玩法及首轮数值已经确认。开发首先验证完整第三人称变身、动态三维度与末影龙流程，以及三版本的回放和防作弊组合，再推进完整功能。

本目录已开始实现 26.3 的 P0 原型：服务端伪装协议、单轮持久三维度和管理员探针命令。它尚不是可开服比赛的 HardRush，完整赛制、轮换、技能、复生、排行榜与历史书均未实现。P0 验收结果与已知阻塞见验证记录，不能把构建成功视为功能通过。

构建需要 Java 25，运行 `scripts/p0-gradle.sh checkP0`。原型 JAR 位于 `platforms/mc263/build/libs/hardrush-0.1.0-p0+mc26.3.jar`；仅供隔离测试，依赖 Fabric Loader 0.19.5 与 Fabric API 0.162.0+26.3。
