# 第 1 轮 交互底座与关键风险启动词

复制下面代码块的全部内容到新 Codex 会话。该会话执行实际第 1 轮开发，本文件的准备不代表开发已经开始。

~~~text
你现在负责 HardRush 无指令大厅五轮交付计划的第 1 轮：交互底座与关键风险。请实际实施本轮代码、必要自动验证和文档交接，不停留在建议或重新规划；只执行第 1 轮，不自动进入下一轮。

仓库：/Users/leifwebber/projects/minecraft/hardrush。
交付范围只有 Minecraft Java Edition 26.3。26.1.2／26.2 正式适配、管理讲台操作界面和全项目多版本发布不在本轮。

先读取下列文件，再核对实际源码：
1. /Users/leifwebber/projects/minecraft/hardrush/AGENTS.md，以及改动目录下适用的其他 AGENTS.md。
2. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-plan.md。
3. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md。
4. /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-interaction-design.md。
5. 查阅 /Users/leifwebber/projects/minecraft/hardrush/docs/requirements.md、/Users/leifwebber/projects/minecraft/hardrush/docs/decisions.md、/Users/leifwebber/projects/minecraft/hardrush/docs/technical-design.md 和 /Users/leifwebber/projects/minecraft/hardrush/docs/acceptance.md 的相关段落。
6. /Users/leifwebber/projects/minecraft/hardrush/docs/人工客户端测试/大厅无指令交互与定向追猎.md。

计划和状态文件已经存在，不要重建、清空或用初始化模板覆盖交接。设计中“本轮不实现代码”描述设计编写阶段；本启动词明确授权当前第 1 轮实施。

启动时记录实际 HEAD、分支、Git 状态和本轮涉及文件的基线。当前工作树可能已有核心、档案、庆祝、脚本和文档改动，禁止 reset、清理或覆盖；不能把已有变更全部归到本轮。不因旧提交或旧测试曾通过，就假定当前基线通过。

在修改代码前，将交接状态的第 1 轮及总表标为“进行中”，记录会话、日期、HEAD 和工作树基线；重要结果或阻塞及时写入。若本轮已经进行中，先恢复现有记录与源码再续作；若已完成，先复核实际交付，不清空历史或重新初始化。

本轮目标是交付后续会话可以直接调用的能力：
1. 建立统一的大厅布局、设施登记、落点用途、菜单上下文和提示优先级。区分只读规则／历史与竞技动作，准备或恢复期间按实际服务能力开放只读入口。
2. 贯通 ProtectionNetworkMixin 与 PlayerIsolationNetworkMixin 两层保护，只放行服务端登记、当前连接、合法身份、距离内的专用设施交互；隔离玩家、普通容器、未登记实体和远程伪造请求继续被拒绝。不要依靠回调或 Mixin 顺序偶然放行。
3. 实现可复用的 26.3 全身皮肤人偶展示基础。使用原玩家 profile，验证两名观察者独立翻页、不同展示代次／实体映射、旧点击失效以及重连／轮换清理。不能退成公共分页或只有头像菜单。原生右键包不带页面版本，旧实体 ID 不能重绑定给新目标后继续接受旧包。
4. 实现新旧追猎语义的规则版本与轮次启用边界，保持旧规则编码、指纹和历史稳定；旧维度字段可保留兼容。加载旧活动轮时必须有实际安全门槛，不静默切换语义、不重新启动投票。新个人追猎完整业务留第 3 轮，未接通能力保持关闭。
5. 为上述能力建立自动化验证，并把接口、生命周期、错误结果、规则迁移条件和后轮接法写清楚。本轮人偶探针必须留下可复用实现，不能只做一次性实验后把风险留到最后。

优先检查这些实际入口，按需要合理拆分类：
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/world/P1LobbyWorld.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p2/protection/mixin/ProtectionNetworkMixin.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p1/player/mixin/PlayerIsolationNetworkMixin.java
- /Users/leifwebber/projects/minecraft/hardrush/platforms/mc263/src/main/java/dev/hardrush/p2/lobby/P2LobbyUi.java
- /Users/leifwebber/projects/minecraft/hardrush/core/src/main/java/dev/hardrush/core/config/RulesConfig.java
- /Users/leifwebber/projects/minecraft/hardrush/core/src/main/java/dev/hardrush/core/round/RoundSnapshotCodec.java

边界：本轮不完整建设营地、不实现空间集结或个人追猎业务、不首次接通复生和书屋；布局契约与最小探针需要做到能被后轮直接复用。不要删改历史证据或擅自改变赛制。

验证要求：执行新增和受影响的 JUnit、无图形 GameTest／原生协议场景。基础入口 scripts/p0-gradle.sh checkP3 -PhardrushP1=true --console=plain 需从仓库执行并先核对任务定义；它不能替代本轮新增协议探针。证明登记交互可达、隔离没有被放宽、双观察者互不影响、旧映射不能点中新目标、规则旧指纹稳定和旧活动轮门槛有效。模拟连接只证明协议／状态，不声称皮肤画面或真实客户端通过。

收尾必须更新 /Users/leifwebber/projects/minecraft/hardrush/docs/lobby-delivery-status.md 的总表和第 1 轮交接，写明实际改动、可复用接口、准确命令、结果、隔离目录、证据和源码标识。人工项目写入既有人工清单并保持未执行，不要求现在就完成截图。实现和本轮自动门槛通过后才能标“开发与自动验证完成”；有缺口就记录部分进展和解除条件，不虚报完成。明确第 2 轮的起点，随后停止，不自行执行第 2 轮。
~~~
