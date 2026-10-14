# HardRush 26.3 调试环境

2026-10-13 已实际接通 MCPFabric、Fabric 服务端及客户端 GameTest、JDWP/JDI 和 spark。该环境针对当前 `platforms/mc263`；26.1.2、26.2 尚未接入或验收。

调试实例与现有 `.local/server-p0`、两个官方原版 HRProbe 客户端分开。带模组的 HRDebug 客户端用于开发与自动化，不能替代“原版客户端可完整游玩”的产品验收。这里的通过也不代表完整 P0、第一人称战斗、真正的传送门或龙战已经验收。

原版客户端人工验收时，代理负责准备环境、明确步骤、采集日志和定位问题；高频战斗、依赖手感或明显适合人工完成的操作直接交给用户。先用服务端回归与受控夹具降低人工操作成本，避免把完整重复战斗交给用户。P0-14 的低血量回归、原版最后一击夹具及人工交接见 [原版末地流程进展](p0-vanilla-end-evidence.md)。

## 固定环境与入口

| 项目 | 固定值 / 地址 |
| --- | --- |
| Minecraft / Java | 26.3 / Java 25 |
| Fabric Loader / API / Loom | 0.19.5 / 0.162.0+26.3 / 1.17.21 |
| MCPFabric | 0.5.1+26.3；MCP 服务端源码提交 `f0acc37be526a7c38e52cf1337f3e60e570b4e9d` |
| spark | 1.10.187-fabric |
| Node / pnpm | 本机 Node 24.12.0、Corepack pnpm 11.21.0；MCPFabric 要求 Node >=22.16 |
| 游戏服务端 | `127.0.0.1:25586`，`.local/debug/server` |
| 服务端 / 客户端 MCP HTTP Bridge | `127.0.0.1:25599` / `127.0.0.1:25601`，必须携带本地 Bearer Token |
| 服务端 / 客户端 JDWP | `127.0.0.1:5005` / `127.0.0.1:5006`，启动时不暂停 |
| 本地测试身份 | HRDebug，离线开发模式、回环监听、服务端白名单与 OP 4 |
| MCP 入口 | `hardrush_server` / `hardrush_client`，stdio；每个入口发现 75 个工具 |

`scripts/debug-tools.lock.json` 固定 JAR 的下载地址、SHA-512，以及 MCP 源码压缩包的提交和 SHA-256；`scripts/mcpfabric-pnpm-lock.yaml` 固定 Node 依赖。准备过程使用 Corepack pnpm，源码和依赖均保存在忽略的 `.local/devtools`。

所有凭据保存在 `.local/debug/<side>/config/mcpfabric.config.json`，不会复制进 MCP 注册配置、源码或日志。启动器在启动时读取 Token。开发工具 JAR 与 GameTest 代码均不进入 HardRush 发布 JAR。

## 准备、启动与停止

从项目根目录运行：

```sh
# 首次准备。需要先阅读并在 .local/server-p0/eula.txt 接受 Minecraft EULA。
# 本机已有用户明确接受的文件；helper 只复用该接受记录，不替新用户同意。
python3 scripts/debug-tools.py prepare

# 写入机器专用的项目配置，并用官方 CLI 注册两个本机 MCP 入口。
# 若同名入口已有其他配置，会拒绝覆盖。
python3 scripts/debug-tools.py register-mcp

# 启动独立服务端 + 客户端；自动等待玩家真正进入世界。
python3 scripts/debug-tools.py start --side client

# 或仅启动服务端。
python3 scripts/debug-tools.py start

python3 scripts/debug-tools.py status
python3 scripts/debug-tools.py status --side client

# 先关客户端，再让服务端正常保存并停止。
python3 scripts/debug-tools.py stop --side client
python3 scripts/debug-tools.py stop
```

对应 Gradle 任务是 `:platforms:mc263:runDebugServer`、`runDebugClient`；命令行需要保持运行时可直接通过 `scripts/p0-gradle.sh` 执行。日常优先使用 Python helper，它检查端口、管理自身启动的进程，并把启动日志保存到 `.local/debug/<side>/launch.log`。

首次启动 macOS 客户端时，helper 在独立配置里关闭首次无障碍引导，保证 Quick Play 可以自动连接；不会修改用户其他 Minecraft 实例。GameTest 客户端还需图形会话；本机 macOS ARM64 已验证，不宣称其他平台也已通过。

## 在 Codex / 本地 Agent 中调用

本机已通过 `codex mcp add` 注册两个 MCP，并用真正的 MCP SDK 完成 initialize、tools/list 和 tools/call；可用 `codex mcp get hardrush_server --json` 或 `hardrush_client` 检查注册。项目 `.codex/config.toml` 由 helper 生成且被忽略。当前项目尚未设为受信任，Codex 未加载项目配置，因此也注册了本机入口。

当前聊天启动时的工具清单可能不会即时刷新。重新加载 MCP 或新开该项目聊天后使用 `hardrush_server` 与 `hardrush_client`。无须等待界面刷新即可使用以下 SDK 脚本，走同一 stdio MCP 链路：

```sh
# 服务端：实际读取状态、HardRush 探针和维度。
node scripts/mcp-check.mjs server
node scripts/mcp-check.mjs server run_command '{"command":"hardrushp0 status"}'

# 客户端：读取玩家与背包、实际移动 1.2 秒并释放输入、保存截图。
node scripts/mcp-check.mjs client
node scripts/mcp-check.mjs client get_self
node scripts/mcp-check.mjs client screenshot
```

客户端与专用服务端是两个 JVM。世界和管理员命令使用 `hardrush_server`；玩家控制、背包和截图使用 `hardrush_client`。客户端 `get_status.serverPresent=false` 表示没有本进程内的单人服务端，不意味着没连接专用服；以 `get_self` 确认玩家已经进入世界。

上游 MCPFabric 工具包括 `get_self`、`get_inventory`、`look`、`set_movement`、`stop_movement`、`screenshot`、`run_command` 等。先查状态，移动后释放输入。`mcp-check.mjs` 也可以调用发现的任意工具：第三参数为工具名，第四参数为 JSON 参数。

直接调用游戏内 HTTP Bridge 的备用入口：

```sh
python3 scripts/debug-tools.py command --command 'hardrushp0 status'
python3 scripts/debug-tools.py rpc --side client --method player.getState
python3 scripts/debug-tools.py rpc --method world.getDimensions
```

输出与截图保存在 `.local/debug/mcp-<side>-evidence.json`、`.local/debug/client/screenshots/`。MCP 进程仅桥接；游戏停机时应返回连接错误，不能据此宣称游戏仍可用。

## Fabric GameTest

```sh
# JUnit 9 项 + 服务端 GameTest（3 项 HardRush + 1 项框架测试）。
scripts/p0-gradle.sh checkP0 --console=plain
# 单跑服务端测试。
scripts/p0-gradle.sh :platforms:mc263:runGameTest --console=plain

# 客户端测试连接 25586，需要已运行独立调试服务端。
# 该测试使用 HRDebug；先停止 live 调试客户端，避免同身份互踢。
python3 scripts/debug-tools.py stop --side client
scripts/p0-gradle.sh :platforms:mc263:runClientGameTest --console=plain
python3 scripts/debug-tools.py start --side client
```

测试源码在 `platforms/mc263/src/gametest`，与发布代码分开：

- 服务端测试：三个运行时维度的种子与所有传送门目标路由、末地战斗对象、同种子幂等、异种子拒绝、三种伪装的启用／解除，以及装备保护。另直接调用原版 `Portal.getPortalDestination` 验证末地进入／返回的 Mixin 路由；仍不替代实际门碰撞、玩家床／锚和完整龙战。
- 客户端测试最初覆盖：连接真正的 HardRush 专用服，通过 Bridge 构造空装伪装，断言附近的 zombie 实体、模拟 F5、截图及解除；当前覆盖范围已扩展如下。

同日 self-view 修复回归已扩展该客户端测试：三种模型的 visible 失败基线、hidden 瞄准／原版空手伤害、每种 20 次显示切换、移动、一次跨维度恢复和解除；同时验证直接相机替换会停止正常移动并选中真实玩家。截图现在明确保存到 `.local/research/self-view/screenshots/`，结构化结果为该目录上一级的 `client-results.json`。相机实验仅由 debugTools 装载，命令 `hardrushdebug camera HRDebug self`／`reset`；100 个服务端 tick 内自动恢复。测试通过不等于原版自动 F5 方案通过，详情见 [self-view 记录](p0-self-view-evidence.md)。

P0-15 随后新增 `HardRushWorldBoundaryGameTest` 的 10 项服务端测试，当前服务端总数为 13 项 HardRush + 1 项框架测试。床／锚、死亡、珍珠、载具、PrepareSpawnTask 模拟重连、进度、时钟／天气及四水晶复召的实际覆盖与夹具范围见 [世界边界记录](p0-world-boundaries.md)。本次没有运行客户端 GameTest；历史 self-view 客户端证据不作新构建视觉通过结论。

早期客户端测试截图在 `platforms/mc263/build/run/clientGameTest/screenshots/`；当前 self-view 回归使用上文的显式输出目录，归档证据见 [self-view 清单](evidence/self-view-20261013/manifest.json)。服务端测试使用可删除的 `build/run/gameTest`；debug-world 和 P0 世界不会被 GameTest 清理。Loom 同时测试时会写共享测试目录，应顺序执行这两种 GameTest。

## JDWP 与实际断点

IDE 的 Remote JVM Attach 配置：主机 `127.0.0.1`，服务端端口 `5005`、客户端 `5006`。源码在本项目中，构建保留调试符号。

无需 IDE 也能用 JDI 程序完成附加、断点、栈帧与变量读取；脚本结束时恢复线程并断开：

```sh
HARDRUSH_JDK="$(cat .local/toolchains/java-home.txt)"
"$HARDRUSH_JDK/bin/java" --add-modules jdk.jdi scripts/JdwpProbe.java \
  5005 dev.hardrush.p0.world.WorldProbe status 'hardrushp0 status'

# 也可观察 create 的 server / seed 参数。
"$HARDRUSH_JDK/bin/java" --add-modules jdk.jdi scripts/JdwpProbe.java \
  5005 dev.hardrush.p0.world.WorldProbe create 'hardrushp0 world create 12345'

# 客户端附加并确认 JVM / 线程。
"$HARDRUSH_JDK/bin/java" --add-modules jdk.jdi scripts/JdwpProbe.java 5006
```

可用 JDK `jdb -attach 127.0.0.1:5005` 交互调试。JDWP 是真实 JVM 调试，不是 MCPFabric 自带工具；Agent 可直接运行上述脚本。断点暂停会影响 tick 耗时，性能测试应在解除断点后执行。

## spark 本地采样与解析

```sh
python3 scripts/debug-tools.py profile --seconds 10
python3 scripts/debug-tools.py profile --side client --seconds 10
```

服务端使用 `spark profiler start --thread * --force-java-sampler`，客户端通过 MCPFabric 的 `chat.send` 调用 Fabric 客户端命令 `sparkc`。结束使用 `profiler stop --save-to-file`，不上传报告。helper 等待这次新产生的非空文件，再通过 spark 自带 protobuf 类型解析，确认确有线程样本并写出同名 `.txt` 摘要。

文件位于 `.local/debug/<side>/config/spark/profile-*.sparkprofile` 与同名 `.txt`，包含持续时间、tick 数、采样器、各线程采样时间，以及服务端／渲染线程的前 20 个调用栈节点。`inclusiveMs` 包含子调用时间，不能把父子节点相加当作 CPU 总耗时。

也可以在游戏内手动使用 `/spark`、`/sparkc`。MCPFabric 的命令反馈会在调度后立即返回；spark 的异步文本输出未必包含在这个返回值中，采样成功以新文件和解码结果为准。

spark 1.10.187 在 `SERVER_STARTING` 后就启动 ping 轮询，但 26.3 的 PlayerList 此时可能未创建，会导致监控线程空指针。本项目独立的 `hardrush-debug-tools` Mixin 在 PlayerList 就绪前返回空 ping 集合；实际重启已确认不再出现该异常。补丁源码在 `src/debugTools`，只由两个 debug 任务装载，固定依赖 spark 1.10.187，未修改官方 JAR，未进入发布 JAR。

## 一键复验与本次证据

```sh
python3 scripts/debug-tools.py verify
```

该命令等待独立服务端就绪，顺序运行 `checkP0`、客户端 GameTest、两个真正 MCP stdio 检查、两侧真实 spark 采样、服务端源码断点／客户端 JDWP 附加，以及发布 JAR 隔离检查。测试期间停止自身管理的 HRDebug 客户端，随后恢复。成功时留下两个调试实例供继续使用，并写 `.local/debug/acceptance.json`。

2026-10-13 调试工具接入时已验证：9 项 JUnit、当时 3 项服务端 GameTest、客户端 GameTest、双方 75 工具发现、真实客户端移动／背包／截图、5005 源码断点、5006 附加、两侧 protobuf 性能样本。随后 P0 补充了直接调用原版末地门的测试，服务端 GameTest 增至 4 项且全部通过，记录见 `.local/p0-evidence/checkP0-final.log`。发布 JAR 的嵌入项仍仅为 core 与 Fantasy；没有 MCPFabric、spark、GameTest 或兼容补丁。

复验日志：`.local/debug/verify.log`、`checkP0.log`、`client-gametest.log`、`mcp-server-check.log`、`mcp-client-check.log`、`jdwp-server.txt`、`jdwp-client.txt`。使用 `acceptance.json` 的时间戳确认最新完整运行；旧日志存在不代表当前复验成功。

## 上游依据

- [MCPFabric 固定提交](https://github.com/denfry/mcpfabric/tree/f0acc37be526a7c38e52cf1337f3e60e570b4e9d)：工具、鉴权与双进程结构。
- [Fabric 自动化测试](https://docs.fabricmc.net/develop/automatic-testing)：GameTest 源集和运行任务；具体 26.3 API 已按本机下载的对应源码 JAR 编译运行确认。
- [spark 命令](https://spark.lucko.me/docs/Command-Usage)：客户端／服务端命令和本地保存采样。
- [OpenAI 官方 MCP 配置说明](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)：stdio、配置文件与 CLI 注册。
