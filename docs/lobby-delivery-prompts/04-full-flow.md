# 第 4 轮 完整无指令闭环启动词

复制下面代码块的全部内容到新 Codex 会话。启动前应已有第 1～3 轮的实际交接。

~~~text
你现在负责 HardRush 无指令大厅五轮交付计划的第 4 轮：完整无指令闭环。请实际接通所有剩余玩家入口并完成自动验证和交接，只执行第 4 轮，不自动进入稳定化或部署。

仓库：/Users/leifwebber/projects/minecraft/hardrush。只覆盖 Minecraft Java Edition 26.3 原版客户端；26.1.2／26.2 正式适配、管理讲台操作界面、暂停菜单辅助功能和全项目发布另排。

先读取：
1. /Users/leifwebber/projects/minecraft/hardrush/AGENTS.md，以及改动目录适用的其他 AGENTS.md。
2. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-plan.md。
3. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md，核验第 1～3 轮场景、菜单、提示和个人追猎接口及证据。
4. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-interaction-design.md。
5. 查阅 /Users/leifwebber/projects/minecraft/hardrush/docs/requirements.md、/Users/leifwebber/projects/minecraft/hardrush/docs/decisions.md、/Users/leifwebber/projects/minecraft/hardrush/docs/technical-design.md 和 /Users/leifwebber/projects/minecraft/hardrush/docs/acceptance.md 的相关段落。
6. /Users/leifwebber/projects/minecraft/hardrush/docs/人工客户端测试/大厅无指令交互与定向追猎.md。
7. 如 /Users/leifwebber/projects/minecraft/hardrush/docs/人工客户端测试/屠龙庆祝与提前返回大厅.md 已随独立庆祝工作交付，补读其中相关步骤；未交付时仍按上述设计和无指令人工清单中的庆祝规则核验，不依赖该可选文件才能启动。

不依赖聊天历史。启动先记录实际 HEAD、分支、Git 状态与本轮文件基线，保护已有改动，不重建状态文件。若第 1～3 轮中任一必需前轮尚未开始或主能力未交付，不替代执行整轮前置、不开始第 4 轮，指出应先使用的启动词。只有已交付前轮的局部直接缺陷允许本轮修复并记归属；无法安全完成时报告真实阻塞，不跳过本轮入口。设计编写阶段的“不实现代码”不限制本次明确授权的开发。

前置核验通过后，在改代码前将第 4 轮及总表标为“进行中”，记录会话、日期、HEAD 和工作树基线；重要结果或阻塞及时写入。已有本轮进行中记录时先恢复再续作；已完成时先复核，不清空历史或重新初始化。

本轮必须完成：
1. 技能物品说明与冷却可理解；新增固定归返物品并合入 MonsterRoles 唯一装备维护来源与 useControl，不能被 repairLoadout 每 tick 清掉。区分启动归返与普通物品／技能中断，避免刚启动即被 interruptReturn 取消。
2. 归返物品右键开始 5 秒引导，行动栏显示进度；移动、攻击、使用物品／技能或受伤中断并说明原因。已引爆苦力怕不能借归返取消。断线、停服和阶段变化取消未完成引导，成功返厅按本轮规则处理 30 秒冷却。
3. 祭坛展示本人资格并确认空装复生，出场冷却期间也可用；不自动消费资格。复用既有资格事务、死亡批次与档案转换，重复点击、关界面和失败重试不重复消费，存储异常继续隔离。
4. 接通救场和无人可追猎反馈。离线人类仍保留资格，只暂停计时，不误团灭；无人类有资格时 60 秒救场，祭坛与中途门可恢复挑战。先发资格并处理苦力怕回厅，再判团灭，不以模型是否存在代替裁决。
5. 书屋提供规则、轮次分页、成绩、参赛名单、分模式榜单、主册／分卷与满包待领。提取共享查询和展示服务，不模拟执行命令再解析聊天。更新成书与失败提示中的必须输入命令说明；准备／异常期间按服务能力保留只读入口。
6. 统一行动栏、顶部状态条、TAB、文字和短音效。执行中进度优先于靠近设施或准星瞄准提示，个人反馈不改变他人选择。
7. 接入现有最多 180 秒庆祝：末地固定在线名单包含人类和怪物，临时鞘翅烟花、原物资封存、龙池直接回独立大厅、已返回者重连留厅、在线全员完成返回可提前结束。保留档案／回放／世界释放门槛，下一轮落圈外重新主动集结，不另造一套存储系统。

主要入口：
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p0/monster/MonsterRoles.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/record/P1HistoryCommands.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/book/P1BookPort.java
- /Users/leifwebber/projects/minecraft/hardrush/core/src/main/java/dev/hardrush/core/book/HistoryBookService.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/record/P1TabRuntime.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/celebration/P1CelebrationRuntime.java

验证要求：通过位置、方块、实体、物品和容器输入，自动串起“集结→极限死亡→追猎→获资格→物品返厅→祭坛复生→救场恢复→通关庆祝→回厅换轮→查战绩领书”。玩家交互不能用旧玩家命令或直接调用核心改身份冒充；夹具可以预置地形、角色或低血量首龙，但要明确范围。复跑相关技能、库存、资格、救场、书籍和庆祝自动回归，新增独立专场按实际实现记录命令，不杜撰入口。

本轮结束时所有正常玩家业务应已可通过无指令入口使用。第五轮只能稳定化和修复，不把首次实现返厅物品、复生、历史分页或庆祝接入留给它。保持投票与手动维度停用，不重写既有资格／档案架构来赶进度。

严格约束：中文沟通；Node 工具用 corepack pnpm。不重置、覆盖或混算已有工作树改动。除非用户另行明确要求，不启动真实客户端、图形测试、浏览器、computer use 或真实游玩 MCP；仅新隔离实例自动测试，不改日常存档和数据库。未经另行授权不提交、推送或部署。

收尾更新 /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md 的第 4 轮及总表，提供每个玩家动作到场景／物品／界面的覆盖表，记录接口、改动、准确命令、结果、隔离路径、证据、人工反馈与未完成项。补齐人工清单中的新增步骤且不代勾。只有全部本轮门槛通过才标开发与自动验证完成；为第 5 轮列出回归矩阵、已知问题和候选包准备项，然后停止。
~~~
