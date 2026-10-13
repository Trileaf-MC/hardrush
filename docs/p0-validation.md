# HardRush P0 技术原型验证

记录日期：2026-10-13。验证仍在执行；本文件中的“通过”只适用于明确列出的单项，不代表 P0 总体通过或可正式开服。

## 当前实现

- 26.3 Fabric 服务端入口及仅 OP 4 可用的 `/hardrushp0` 探针命令。
- zombie、skeleton、creeper 的服务端数据包外观原型：旁人实体替换、本人的完整尺寸替身，以及解除和断线清理。当前仅接受全空装备，不实现最终装备与角色技能。
- 一个持久的轮次主世界、下界和末地；种子、轮次 ID、生成器与出生点保存至清单。包括传送门和重生路由适配、独立末影龙战斗数据。不实现自动轮换或世界清理。
- 隔离服务端与官方原版客户端准备脚本。核心赛制模块目前为空，不能把 `core:test NO-SOURCE` 计为测试通过。

## 锁定环境

| 项目 | 实际值 |
| --- | --- |
| Minecraft | 26.3，官方非混淆客户端／服务端 |
| Java | Temurin 25.0.4.1+1，本地独立安装 |
| Gradle / Loom | 9.6.0 / 1.17.21 |
| Fabric Loader / API | 0.19.5 / 0.162.0+26.3 |
| Fantasy | 0.8.4+26.3，嵌入 JAR |
| 测试框架 | JUnit 5.14.0 |
| 主机 | macOS，Apple M2 Pro，ARM64 |
| 服务端 | `127.0.0.1:25585`，独立 `.local/server-p0` |
| 客户端身份 | 仅本地测试 HRProbeA / HRProbeB，官方离线开发模式，不读取已有账号或启动器凭据 |
| 客户端内容 | 官方清单 5,230 个文件均验证 SHA-1；无 mods、无资源包 |

本地服务端使用离线身份白名单、仅回环监听；该配置专用于这次隔离测试，不是公网部署配置。用户已明确接受本次 EULA 并授权启动。准备脚本仍默认写入 `eula=false`，不会自动替其他使用者同意条款。

仓库尚未初始化 Git，因此本次没有 commit ID。构建与源码校验值将在本次验证收尾时保存。第三方来源及许可证见 [组件清单](../THIRD-PARTY.md)。

## 已执行结果

| 检查 | 结果与范围 |
| --- | --- |
| `scripts/p0-gradle.sh checkP0 --console=plain` | 通过：编译、JAR、9 项 WorldManifest JUnit；失败 0、错误 0、跳过 0 |
| Fabric 服务端启动 | 通过：实际加载 26.3、Loader、Fabric API、Fantasy 和 HardRush；日志确认服务端就绪 |
| 首次创建三个动态维度 | 初测失败；已定位并修复“未加载区块时高度查询返回世界底部”的出生点问题 |
| 保留失败后的清单并重启恢复 | 通过：同轮 ID、seed=12345；补全安全 spawn=(96,132,-23)，三个维度均就绪 |
| 相同种子重复 create | 通过：不产生新轮 ID，保留现有维度 |
| 不同种子 create | 通过：拒绝 seed=54321，原轮仍可用，没有覆盖存档 |
| 原版客户端实际加入、F5、第一人称 | 执行中；文件准备或 Java 进程启动不等同于通过 |
| 实际传送门与首次末影龙 | 执行中；`dragonFight=true` 仅证明对象初始化，不能证明完整龙战通过 |
| 26.1.2 / 26.2 | 未执行，属于 P0b |
| ServerReplay、AntiXray、Grim | 未安装联合测试，不作兼容结论 |
| 性能与 20 人负载 | 未执行 |

本次轮次：`99898f68-a364-4469-b837-6664da246c39`。维度前缀 `hardrush:p0_99898f68a3644469b8376664da246c39/`，后缀分别为 `overworld`、`nether`、`end`。

## 复现方法

安装 Java 25 后设置 `HARDRUSH_JAVA_HOME` 或 `JAVA_HOME`。本次机器也可使用 `.local/toolchains/java-home.txt` 中的本地路径。

```sh
scripts/p0-gradle.sh checkP0 --console=plain
python3 scripts/p0-server.py prepare
# 阅读并接受 Minecraft EULA 后，手动设置隔离目录中的 eula.txt。
python3 scripts/p0-server.py run
```

在服务端控制台执行（游戏聊天中加 `/`）：

```text
hardrushp0 status
hardrushp0 world create 12345
hardrushp0 world enter HRProbeA overworld
hardrushp0 world enter HRProbeA nether
hardrushp0 world enter HRProbeA end
hardrushp0 disguise HRProbeA zombie
hardrushp0 status HRProbeA
hardrushp0 clear HRProbeA
save-all flush
stop
```

`world enter` 是管理员传送，不能替代真正走传送门的验收。首次 `create` 后不允许修改种子；它是单轮恢复原型，不是轮换接口。

macOS ARM64 原版客户端：`python3 scripts/p0-client.py prepare` 下载并验证官方文件，`verify` 复核内容，`launch HRProbeA` 或 `launch HRProbeB` 启动独立配置。其他操作系统尚无这个测试启动器的支持声明。所有游戏数据保存在 `.local`，没有修改用户既有 Minecraft 安装。

## 已知技术风险

完整尺寸本人替身可能被原版客户端选为眼内距离零的射线目标。当前状态命令明确显示 `selfTargeting=unresolved`；仅看到正确 F5 模型不能判定第一人称攻击正常。需结合实际客户端结果决定修复路线，不允许用缩小替身、普通玩家身体或客户端模组替代既定需求。

原型仍沿用玩家物理尺寸，并在装备、死亡、旁观或乘坐变化时安全解除外观；这些都是待实现／待验证范围，不是最终玩法。

世界初始准备目前同步执行，只用于 P0。正式版本须按 tick 分配生成预算，并完成轮换、故障恢复与实体引用释放测试。

首次开发启动曾因 Mixin 配置包范围覆盖入口类而失败；已将伪装和世界 Mixin 独立为各自的 `.mixin` 包配置，之后服务端成功启动。Minecraft 启动失败时 Gradle 仍可能返回成功，因此此次以服务端日志和实际状态为准。
