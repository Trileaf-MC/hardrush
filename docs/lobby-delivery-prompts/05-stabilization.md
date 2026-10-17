# 第 5 轮 稳定化与候选包启动词

复制下面代码块的全部内容到新 Codex 会话。前四轮玩家主流程应已实现，第五轮核验、修复并交付候选包。

~~~text
你现在负责 HardRush 无指令大厅五轮交付计划的第 5 轮：稳定化与候选包交付。请实际完成集成回归、必要修复、生产制品与隔离演示准备、文档和最终交接，不自动发布或部署。

仓库：/Users/leifwebber/projects/minecraft/hardrush。只交付 Minecraft Java Edition 26.3；26.1.2／26.2 正式适配及整个项目 P0～P4 发布验收另排。

先读取：
1. /Users/leifwebber/projects/minecraft/hardrush/AGENTS.md，以及改动目录适用的其他 AGENTS.md。
2. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-plan.md。
3. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md 的全部交接，核验前四轮源码、接口和证据。
4. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-interaction-design.md。
5. 查阅 /Users/leifwebber/projects/minecraft/hardrush/docs/requirements.md、/Users/leifwebber/projects/minecraft/hardrush/docs/decisions.md、/Users/leifwebber/projects/minecraft/hardrush/docs/technical-design.md 和 /Users/leifwebber/projects/minecraft/hardrush/docs/acceptance.md 的相关段落。
6. /Users/leifwebber/projects/minecraft/hardrush/docs/人工客户端测试/大厅无指令交互与定向追猎.md。
7. 前轮已收到的人工反馈；如 /Users/leifwebber/projects/minecraft/hardrush/docs/人工客户端测试/屠龙庆祝与提前返回大厅.md 已随独立庆祝工作交付，补读相关步骤。未交付时仍按上述设计和无指令人工清单核验，不依赖该可选文件才能启动。

不依赖旧聊天。启动记录 HEAD、分支、Git 状态及本轮基线，保护工作树已有改动，不重置或清空交接。不要只信前轮“完成”标签，先核验所有主流程真实存在。若第 1～4 轮中任一必需前轮尚未开始或主能力未交付，不替代执行整轮前置、不开始第 5 轮，指出应先使用的启动词。只有已交付前轮的局部直接缺陷允许本轮修复并记归属；整套人偶、集结恢复或书屋未实现时明确报告未达门槛，不能假报完成或偷偷增加第六轮。设计阶段“不实现代码”不限制本启动词授权的修复。

前置核验通过后，在改代码前将第 5 轮及总表标为“进行中”，记录会话、日期、HEAD 和工作树基线；重要结果或阻塞及时写入。已有本轮进行中记录时先恢复再续作；已完成时先复核，不清空历史或重新初始化。

本轮任务：
1. 建立并运行独立无指令闭环专场，覆盖普通／极限、多人操作、连续多轮以及场景入口。玩家动作由实际位置、方块、实体、物品与容器协议触发；不靠命令或直接改核心身份冒充。
2. 复跑受影响的 JUnit、GameTest 和新 JVM 正常恢复，包含 COUNTDOWN 原准备者与余量、活动身份、救场、庆祝、轮换、离线跨轮。维护专场互斥和新建独立存档，不能混跑会改同一大厅的夹具。
3. 回归目标生命、跨维度原预算、按发起者的 3 秒间隔、旧页面／旧连接、重复请求、迟到持久化回调、目标失效、传送前复核、补偿及存储隔离。确认旧规则编码／指纹稳定，旧活动轮门槛有效，不重新启用投票或手动维度。
4. 检查模型、页面映射、菜单、状态条和实体引用在翻页、重连、轮换后被清理，无重复或持续增长。执行约 20 名连接规模的可自动化协议／状态检查，明确模拟连接不等于 20 名真实客户端画面和操作验收。
5. 修复前四轮回归和已有人工反馈。重点排除错误追猎目标、重复资格消费、串轮物资、错误传送、错误恢复放行；不通过修改测试预期或降低安全约束来消除失败。
6. 生成并审计生产 JAR，确认不含 GameTest、MCPFabric、开发探针或其他测试辅助代码；记录准确依赖、源码标识、制品 SHA-256。准备新的独立演示实例和可复制运行命令，不操作或覆盖日常实例。
7. 完成安装、升级、旧活动轮处理、备份与回退说明。新旧编码不兼容时明确需要对应一致性备份，不能承诺随意回退或只回退 JAR 即可恢复；不修改历史成绩或规则指纹。
8. 整理最终人工验收路线、未测试项和已知限制，说明26.3开发及自动验证状态与人工／多版本／整个项目发布状态的区别。

现有验证与制品入口需要先读实际文件确认：
- /Users/leifwebber/projects/minecraft/hardrush/build.gradle
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/build.gradle
- /Users/leifwebber/projects/minecraft/hardrush/scripts/p0-gradle.sh
- /Users/leifwebber/projects/minecraft/hardrush/scripts/p1-server-restart.py
- /Users/leifwebber/projects/minecraft/hardrush/scripts/p3-server-crash.py
- /Users/leifwebber/projects/minecraft/hardrush/scripts/demo.py

基础 checkP3 并不运行所有新增专场。执行前轮实际新增的命令与本轮集成入口，并按风险选择受影响的恢复／故障回归；没有新变化时不机械重跑全部强杀矩阵。只使用隔离目录，强杀仅允许测试 runner 自己创建的子服务端，不触碰用户正在运行的服务器。预置首龙、模拟网络和测试时钟如实记录，不冒充满血生存或真实客户端验证。

严格约束：中文沟通；Node 工具使用 corepack pnpm。未经用户另行明确要求，不启动真实客户端、图形测试、浏览器、computer use 或真实游玩 MCP，不代做人工截图验收。未经另行授权不提交、推送、发布、部署，不修改日常数据库或存档。候选制品与隔离演示准备可以完成，但不等于已获发布授权。

收尾更新 /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md 的第 5 轮、总表与最终结论，写明前四轮复核、准确测试命令／结果／隔离路径／日志、修复、最终源码及制品校验、安装与演示入口、升级回退说明、剩余人工项目。同步验收与使用文档，保留失败和限制记录。只有所有必需自动门槛通过且无未处理关键问题时标开发与自动验证完成；未运行人工项目保持未验收，不勾选全项目阶段或多版本发布通过。完成交付后停止。
~~~
