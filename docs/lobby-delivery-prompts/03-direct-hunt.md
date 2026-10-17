# 第 3 轮 皮肤模型与定向追猎启动词

复制下面代码块的全部内容到新 Codex 会话。启动前应已有第 1～2 轮的实现与验证记录。

~~~text
你现在负责 HardRush 无指令大厅五轮计划的第 3 轮：皮肤庭院与个人定向追猎。请实际实施本轮代码、自动验证和交接，只做第 3 轮，不自动推进第 4 轮。

仓库：/Users/leifwebber/projects/minecraft/hardrush。只交付 Minecraft Java Edition 26.3；另两版正式适配和管理讲台操作界面不在范围内。

先读取：
1. /Users/leifwebber/projects/minecraft/hardrush/AGENTS.md，以及改动目录适用的其他 AGENTS.md。
2. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-plan.md。
3. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md，核验第 1～2 轮人偶、设施、规则、布局与恢复接口及证据。
4. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-interaction-design.md。
5. 查阅 /Users/leifwebber/projects/minecraft/hardrush/docs/requirements.md、/Users/leifwebber/projects/minecraft/hardrush/docs/decisions.md、/Users/leifwebber/projects/minecraft/hardrush/docs/technical-design.md 和 /Users/leifwebber/projects/minecraft/hardrush/docs/acceptance.md 的相关段落。
6. /Users/leifwebber/projects/minecraft/hardrush/docs/人工客户端测试/大厅无指令交互与定向追猎.md。

从文件和源码恢复上下文，不依赖原讨论。记录当前 HEAD、分支、Git 状态和本轮基线，保护已有改动。若第 1～2 轮中任一必需前轮尚未开始或主能力未交付，不替代执行整轮前置、不开始第 3 轮，指出应先使用的启动词。只有已交付前轮的局部直接缺陷允许本轮修复并记录归属；无法安全补齐就报告实际失败与解除条件。不要清空交接。设计当时“本轮不写代码”不限制本启动词明确授权的实施。

前置核验通过后，在改代码前将第 3 轮及总表标为“进行中”，记录会话、日期、HEAD 和工作树基线；重要结果或阻塞及时写入。已有本轮进行中记录时先恢复再续作；已完成时先复核，不清空历史或重新初始化。

本轮必须完成：
1. 使用前轮投影与设施底座接通角色展位和追猎庭院。按 UUID 稳定展位，展示原皮肤、名字、在线可追猎／暂离／死亡／复生状态；本人翻页不影响他人，旧页面点击不选到替换目标，模型不计为玩家。不得泄露装备、血量、坐标、方向或距离。
2. 冷却结束后右键模型直接发起个人追猎，不追加投票、维度选择、15 秒等待或第二道出场门；冷却中只反馈，不登记自动出场。
3. 重构出场请求，冻结发起者轮次、生命与出场序号，同时冻结目标轮次、UUID、生命序号；搜索、提交回调及传送前都验证。相同 UUID 死亡后复生不能被旧请求追上。
4. 按目标当前的本轮维度搜索。同一生命换维度可在原预算内重搜，但累计不得超过 2 秒／64 候选；目标离线、死亡、身份或生命改变时明确取消，不随机换人。失败后按发起怪物 UUID 等待 3 秒，切目标也不能绕过。
5. 保留当前安全距离和地形规则、提交后再应用、传送前复核和内部取消补偿。真实世界、档案和存储故障继续隔离，不能假报成功。
6. 角色选择和旧 monsterDimension 解耦，系统决定当前维度；停用新规则下全部 vote、dimension、无目标 deploy、投票调度、菜单选项和随机重选路径。保留必要历史兼容与旧规则指纹，不为清理字段重写历史。
7. 迁移读取 votes、命令或旧投票菜单的自动夹具。新入口验证要经过实际实体右键与受保护路由，不用直接改核心状态冒充人偶操作。

重点代码入口：
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p2/lobby/P2LobbyRuntime.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p2/lobby/P2LobbyUi.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p2/hunt/P2SafeDeployment.java
- /Users/leifwebber/projects/minecraft/hardrush/core/src/main/java/dev/hardrush/core/hunt/HuntVotingService.java
- /Users/leifwebber/projects/minecraft/hardrush/core/src/main/java/dev/hardrush/core/round/Participant.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/gametest/java/dev/hardrush/gametest/P2RuntimeLobbyGameTest.java

验收必须覆盖：极限死亡→选角→右键模型→安全出场→败退回厅；三维度路由、不同怪物各选目标、多人同目标、双手与快速连点、旧页面／旧连接、搜索中换维度共享预算、死亡后复生、离线、提交后目标移动或地形变化、补偿与故障隔离。出场后目标换维度不会自动传送怪物，攻击其他合法人类和其击杀资格仍依既有规则。

运行新增和受影响的 JUnit、无图形 GameTest／协议场景以及迁移后的 P2 专场，准确记录筛选器、属性和独立目录。现有专场入口 scripts/p0-gradle.sh :platforms:mc263:runGameTest -PhardrushP1=true -PhardrushP2Lobby=true --console=plain 需先核对配置并完成夹具迁移；旧投票结果不能算新 S03 通过。

边界：本轮交付追猎完整路径，技能物品返厅、祭坛确认、书屋和全部状态整合留第 4 轮；继续维护前轮已完成的人类流程。多人同目标不加配额，不限制单次追猎时长，不修改资格或安全落点来简化实现。

严格约束：中文沟通；Node 工具使用 corepack pnpm。未经用户另行明确要求，不启动真实客户端、图形测试、浏览器、computer use 或真实游玩 MCP。自动场景只用新隔离存档，不碰日常数据库或服务器；预置夹具与模拟连接如实说明。未经另行授权不提交、推送或部署。

收尾更新 /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md 的第 3 轮与总表，记录模型映射／定向请求／角色接口、旧路径停用清单、基线和改动、准确验证命令、隔离路径、证据及未完成项。更新人工清单，真实皮肤和选取手感保持待人工。只有本轮门槛通过才标开发与自动验证完成，并写清第 4 轮如何接技能、归返、祭坛与提示，然后停止。
~~~
