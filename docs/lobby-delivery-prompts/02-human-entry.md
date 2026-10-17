# 第 2 轮 营地与人类入场启动词

复制下面代码块的全部内容到新 Codex 会话。启动前应已有第 1 轮的实际实现和交接。

~~~text
你现在负责 HardRush 无指令大厅五轮交付计划的第 2 轮：营地与人类入场。请实际完成本轮实现、自动验证和交接，只执行第 2 轮，不自动进入下一轮。

仓库：/Users/leifwebber/projects/minecraft/hardrush。范围仅 Minecraft Java Edition 26.3，原版客户端，无强制资源包。26.1.2／26.2、管理操作界面和全项目多版本发布另排。

先读取：
1. /Users/leifwebber/projects/minecraft/hardrush/AGENTS.md，以及改动目录适用的其他 AGENTS.md。
2. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-plan.md。
3. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md，重点第 1 轮接口、验证证据和第 2 轮当前状态。
4. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-interaction-design.md。
5. 查阅 /Users/leifwebber/projects/minecraft/hardrush/docs/requirements.md、/Users/leifwebber/projects/minecraft/hardrush/docs/decisions.md、/Users/leifwebber/projects/minecraft/hardrush/docs/technical-design.md 和 /Users/leifwebber/projects/minecraft/hardrush/docs/acceptance.md 的相关段落。
6. /Users/leifwebber/projects/minecraft/hardrush/docs/人工客户端测试/大厅无指令交互与定向追猎.md。

不要依赖之前的聊天历史。启动先记录实际 HEAD、分支、Git 状态和改动基线，再核验第 1 轮接口与证据，不能只相信“已完成”标签。若第 1 轮尚未开始或主能力未交付，不替代执行整轮前置、不开始第 2 轮，指出应先使用第 1 轮启动词。只有已交付前轮的局部直接缺陷允许本轮修复并记归属；无法安全补齐时报告实际阻塞，不绕过门槛。不要重建或清空状态文件。设计阶段“本轮不实现代码”不限制本启动词明确授权的开发。

前置核验通过后，在改代码前将第 2 轮及总表标为“进行中”，记录会话、日期、HEAD 和工作树基线；重要结果或阻塞及时写入。已有本轮进行中记录时先恢复再续作；已完成时先复核，不清空历史或重新初始化。

本轮交付一条可用的人类参赛路径：
1. 按统一布局建设约 64×48 核心营地，完成实际覆盖的有界异步加载、布局版本、幂等施工和设施登记。既有仅 3×3 出生区块加载不等于营地就绪；不每次启动覆盖已有大厅地形。
2. 接好中央、怪物、圈外等候与正常恢复圈内落点，保持健康已核验大厅玩家自由移动，不能每 tick 拉回出生点或全厅冻结。世界或建造未就绪时关闭入口；单人档案未就绪不挡其他合格玩家。
3. 贯通 RoundEngine、P1LobbySession、Runtime 和 profiles，实现一次入圈意图的权威报名准备操作或等价完整保护。不能只顺序发送两个独立 join／ready 请求。
4. 站入准备、离开取消；首人触发 30 秒，后人加入不重置，人数归零才取消，下次从完整 30 秒开始。一人可开局，最后一秒可离开。正常跳跃、转头、背包和 TAB 不取消，边界避免抖动。
5. 处理登记期间离圈、快速重进、普通断线、过期提交和归零前位置终验。离圈后迟到回调不能重新准备或传送。普通断线取消准备，重连落圈外。
6. 实现中途门确认：确认前关闭放弃；请求受理后关闭不撤销已提交转换，也不能重复加入。极限死者不可靠门或集结恢复人类；存活人类重连恢复原位置，普通死亡保留床／锚和原版重生。
7. 正常停服保留开局余量和原准备者。世界就绪且任一原准备 UUID 核验、上线并恢复圈内站位即可续计，不等所有人，不允许无关 UUID 放行。不安全恢复站位落圈外并说明；脏退出继续隔离。

优先检查与复用：
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/world/P1LobbyWorld.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/P1LobbyRuntime.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/P1LobbySession.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/player/P1PlayerProfiles.java
- /Users/leifwebber/projects/minecraft/hardrush/core/src/main/java/dev/hardrush/core/round/RoundEngine.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/gametest/java/dev/hardrush/gametest/P1ServerRestartRegression.java
- /Users/leifwebber/projects/minecraft/hardrush/scripts/p1-server-restart.py

边界：只完成营地与人类路径。可以建立角色、庭院、祭坛和书屋的物理位置，但未接通功能需明确关闭，不假装可用；定向追猎第 3 轮、完整外围闭环第 4 轮。正式挑战世界仍使用自然出生地形，不添加石台或保护圈。

验证要求：新增和受影响的规则／Session／布局测试、无图形服务端位置与设施交互，自动串起集结、开赛、普通死亡重生、中途加入。扩展并实际运行 COUNTDOWN 新 JVM 专场，验证原余量、无关 UUID、仅一原准备者回归、恢复圈内、再次正常停服和过期结果。scripts/p1-server-restart.py --phase COUNTDOWN 是现有入口，执行前核对脚本及隔离路径；不得用纯内存时钟测试冒充整服重启。基础 checkP3 不能替代新增专场。

严格约束：中文沟通；需要 Node 工具时使用 corepack pnpm。不重置、清理、覆盖工作树已有改动。未经用户另行明确要求，不启动真实客户端、图形测试、浏览器、computer use 或真实游玩 MCP；只运行新建隔离实例的自动验证，不改日常存档、数据库。未经另行授权不提交、推送或部署，不降低恢复与持久化要求来通过测试。

收尾更新 /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md 的第 2 轮和总表，记录基线、改动、布局／落点／准备 API、受理语义、准确验证命令与隔离目录、结果和证据。更新人工清单中的布局与集结待验项。门槛未过不得标完成；门槛通过后明确第 3 轮接入角色展位、庭院与请求的入口，然后停止。
~~~
