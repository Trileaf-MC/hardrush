# 26.3 MVP 本地演示

日期：2026-10-15。本入口把现有普通／极限玩法做成可重复创建的独立演示实例。运行的是构建后的 HardRush JAR、Fabric API 和固定版本 Fabric 启动器，不使用开发 classpath、测试模组、MCPFabric 或 spark。客户端使用 **Minecraft Java Edition 26.3 原版**，服务端使用 **Java 25**，准备脚本使用 **Python 3.11 或更新版本**。这是开发演示，P0～P4 的正式验收状态继续以 [实施计划](implementation-plan.md) 为准。

## 创建与启动

在仓库根目录执行：

```sh
python3 scripts/demo.py prepare
python3 scripts/demo.py run <上一步输出的实例UUID>
```

默认是极限模式，监听 `127.0.0.1:25587`，开启正版认证。脚本优先使用 `HARDRUSH_JAVA_HOME`，其次 `JAVA_HOME`，再使用本仓库 `.local/toolchains/java-home.txt` 中已准备的 Java 25。首次启动由 [Fabric 官方启动器](https://meta.fabricmc.net/v2/versions/loader/26.3/0.19.5/1.1.2/server/jar) 下载 Minecraft 服务端和运行库，需要联网；启动器自身的版本和 SHA-256 固定在 `scripts/demo.lock.json`。

`prepare` 每次创建全新 UUID 存档，不覆盖旧实例。若 `.local/server-p0/eula.txt` 已包含用户确认的 `eula=true`，沿用该确认；否则新实例保持 `eula=false`，需阅读 Minecraft EULA 后自行编辑新实例的 `eula.txt`。脚本不会代替用户接受条款。

两台机器在同一局域网演示时，显式创建监听所有 IPv4 地址的实例：

```sh
python3 scripts/demo.py prepare --mode hardcore --bind 0.0.0.0 --port 25587
python3 scripts/demo.py run <实例UUID>
```

其他机器连接服务端电脑的局域网 IP 和 `25587` 端口，本机仍可连接 `127.0.0.1:25587`。正版认证始终开启。默认没有预设 OP；需要管理时，在服务端控制台输入 `op <正版玩家名>`。不要把正式比赛的身份或奖励演示替换成 `/hardrushp0` 探针。

普通模式另建实例：

```sh
python3 scripts/demo.py prepare --mode normal --port 25588
```

两种模式使用不同存档；同一实例重启继续读取数据库中保存的当前轮和下轮规则，`initialMode` 仅在首次新库生效。控制台输入 `stop` 正常保存，待 Java 退出后可用同一个 UUID 重开。强制结束后会进入恢复隔离，按 [恢复处置](p3-recovery-controls.md) 操作；当前不能核验后直接继续原活动轮次。

## 最短试玩路线

界面、输入和多人战斗由用户按 [MVP 人工演示清单](人工客户端测试/MVP演示.md) 验证。本批只进行了无图形服务端检查。

1. 等控制台 `hardrush status` 显示 `WAITING` 和“世界准备：已完成”。原版玩家加入后用 `/hardrush menu` 打开大厅，也可 `/hardrush join`、`/hardrush ready`。首位就绪触发 30 秒倒计时；第二名玩家可在倒计时中加入并就绪。
2. 人类离开出生保护区后进行正常生存、PvP、三维度速通。按 TAB 查看有效计时和人数，最后一击末影龙决定胜者。
3. 极限模式至少两名玩家：一名人类死亡后重生到怪物大厅，通过菜单或 `/hardrush role zombie|skeleton|creeper` 选角，选择维度、投票，等待 30 秒出场冷却后 `/hardrush deploy`。目标人类须先离开出生保护区，安全搜索失败会留厅重试。
4. `/hardrush skill` 或角色技能物品触发技能；`/hardrush return` 引导 5 秒返厅，移动、攻击、受伤或使用技能会取消。怪物直接击杀合法人类获得一份复生资格，返厅后 `/hardrush revive` 空装复生；最后一名人类死亡且有资格时有 60 秒救场窗口。
5. 通关后有 600 秒庆祝，随后安全释放旧轮并准备新轮。历史通过 `/hardrush history`、`/hardrush leaderboard` 和历史书查看。演示中如需提前结束已就绪轮次，使用 `/hardrush admin abort <原因>`，核对预览后 `/hardrush admin confirm <票据>`；这会形成真实中止记录，不等同于首龙胜利。

默认赛制、出生保护、物品限制、出场距离和技能冷却沿用正式配置，没有为 Demo 缩短时间或绕过资格提交。完整通过条件仍见既有 P1／P2 人工清单。

## 制品与自动检查

```sh
python3 scripts/demo.py build
python3 scripts/demo.py smoke --timeout 300
python3 -m unittest discover -s scripts -p 'test_demo.py' -v
scripts/p0-gradle.sh checkP3 -PhardrushP1=true --console=plain
```

`build` 输出 `.local/demo/bundles/<UUID>`，包含两个顶层模组 JAR、启动器、许可证和 `manifest.json`。清单记录源码摘要、构建时 Git 状态和制品 SHA-256；递归检查 HardRush 嵌入依赖，拒绝本项目测试代码及调试工具。Fabric API 自带的 GameTest API 模块属于官方运行依赖，不是本项目测试夹具。现有模组版本名仍为 `0.1.0-p0+mc26.3`，不把此入口冒充正式发布版本。

`prepare` 复制制品到 `.local/demo/instances/<UUID>`。启动前复核制品清单、JAR 内容摘要、额外模组和实例配置；已有存档从不由构建更新覆盖。更新代码后重新 prepare；保存旧 UUID 即可继续运行旧构建和存档。所有游戏文件、缓存和日志均保留在忽略的 `.local` 中。

`smoke` 为普通／极限各新建一份存档，通过真实生产 JAR 启动到三维度准备完成，执行控制台 `stop`，再由另一 Java 进程打开同一存档。检查模式、轮次 ID、SQLite 完整性、正常退出和日志错误；不创建玩家、不模拟客户端游玩，不证明死亡／复生、走门、伪装画面或完整首龙流程。结果位于制品的 `smoke-result.json`，各实例保留逐进程日志和 JSON；失败也保留现场。

本批证据见 [E29 清单](evidence/mvp-demo-20261015/manifest.json)。回放、AntiXray、Grim 完整集成、故障恢复剩余项、20 人性能和多版本发布仍在 P3／P4；本入口没有据此放行任何阶段。

2026-10-15 验证结果：342 项 JUnit、101 项常规必需无图形 GameTest、12 项 Demo 制品／隔离入口测试通过。最终生产制品在普通／极限两个新存档、4 个不同 PID 的专用服务端 JVM 中完成三维度准备、正常停止与同轮重开；数据库检查点均为 `cleanShutdown=true`、`WAITING`，没有运行客户端。
