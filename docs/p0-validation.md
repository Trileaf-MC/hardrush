# HardRush P0 技术原型验证

记录日期：2026-10-13。**P0 未通过：完整本人替身在 visible 状态违反 C03，第一人称被自身模型遮挡，准星选中自身替身；手动隐藏尚不能同时满足原版 F5 要求。** 三种怪物的完整 F5 外观可见，世界原型的主要流程已取得运行证据；这些单项不能抵消完整视角操作的失败。本轮产出是可构建、可复现的技术原型及明确的阻塞结论，不能进入正式玩法开发或发布验收。后续执行状态与依赖见 [开发任务清单](implementation-plan.md)。

后续同日已完成相机路线研究和手动 `selfview hidden|visible` 修复尝试。hidden 模式恢复了基础第一人称瞄准、攻击和移动，但需要额外切换，仍不能自动配合原版 F5。新一轮证据与验证见 [self-view 修复记录](p0-self-view-evidence.md#2026-10-13相机实验与手动显示切换)，早期失败截图和制品清单继续保留，P0 仍未通过。

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

验证开始时目录尚无 Git；期间用户初始化仓库，早期验证基线为 `1933296`，各批次证据另记录未提交源码与制品校验值。第三方来源及许可证见 [组件清单](../THIRD-PARTY.md)。

## 早期主验证结果

下表保留首轮主验证范围；同日后续手动显示切换、相机实验及客户端回归见 [自身外观记录](p0-self-view-evidence.md)。两批结果都未使 P0 通过。

| 检查 | 结果与范围 |
| --- | --- |
| `scripts/p0-gradle.sh checkP0 --console=plain` | 通过：编译、JAR、9 项 WorldManifest JUnit（0 失败／错误／跳过），以及 3 项 HardRush 服务端 GameTest + 1 项框架测试。新增用例直接调用原版末地门方法，覆盖 Mixin 进入／返回路由 |
| Fabric 服务端启动 | 通过：实际加载 26.3、Loader、Fabric API、Fantasy 和 HardRush；日志确认服务端就绪 |
| 首次创建三个动态维度 | 初测失败；已定位并修复“未加载区块时高度查询返回世界底部”的出生点问题 |
| 保留失败后的清单并重启恢复 | 通过：同轮 ID、seed=12345；补全安全 spawn=(96,132,-23)，三个维度均就绪 |
| 相同种子重复 create | 通过：不产生新轮 ID，保留现有维度 |
| 不同种子 create | 通过：拒绝 seed=54321，原轮仍可用，没有覆盖存档 |
| C01 原版连接 | 两个无模组、无资源包的官方 26.3 客户端实际加入同一 Fabric 服务端；这里只验连接，不代表未实现的全玩法可用 |
| C02 完整怪物外观 | 空装备下三种怪物均验证本人 F5 前／后视角及旁人显示，保留 12 张原版截图；完整装备、受伤尺寸及动作回归仍未通过 |
| C03 第一人称 | **失败**：三种模型均遮挡相机；MCPFabric 调试客户端进一步读取原版 `Minecraft.hitResult`，均选中自身怪物替身，解除后恢复 `none` |
| 下界门往返 | 原版 HRProbeA 实际触发进入本轮下界并返回原主世界门，未落到大厅 |
| 末地门进入 | MCPFabric 调试客户端从相邻方块实际走入末地门成功，进入同轮末地；该段不是无模组客户端验收 |
| 原生首龙 | HRProbeA 在独立末地看到龙战血条；调试轮次也生成原生龙，没有使用 summon 代替 |
| 首龙重启恢复 | 调试实例正常关闭／重启后，轮次、种子和龙 UUID 不变；只返回 1 个存活龙主体 |
| 击龙及自然出口 | 调试客户端在临时增强攻击属性、管理员靠近定位的测试条件下发送攻击；触发原版死亡流程、解放末地进度和自然出口门。攻击属性恢复为 1；这不是常规生存战斗或最后一击计分验收 |
| 自然出口返回 | 调试客户端落入首龙死亡后自然生成的出口门，返回同轮主世界出生点 `(96.5,132,-22.5)` |
| 26.1.2 / 26.2 | 未执行，属于 P0b |
| ServerReplay、AntiXray、Grim | 未安装联合测试，不作兼容结论 |
| 性能与 20 人负载 | 未执行 |

本次轮次：`99898f68-a364-4469-b837-6664da246c39`。维度前缀 `hardrush:p0_99898f68a3644469b8376664da246c39/`，后缀分别为 `overworld`、`nether`、`end`。

后续 MCPFabric 调试轮次独立为 `8e7ef667-ad98-4507-97fc-439847d19460`，原生龙 UUID 为 `1b5fc10e-660c-4e53-81e3-dce1fa8e4219`。重启前后证据均来自此轮，未混用早期存档。首次实体查询返回 9 个记录是 1 个龙主体和 8 个龙分部；使用 `onlyLiving=true` 后仅 1 个主体，不是 9 只龙。

## 视觉与结构化证据

以下图片由原版客户端 F2 保存，文件未编辑：

| 形态 | 本人 F5 | 旁人 | 第一人称失败 |
| --- | --- | --- | --- |
| 僵尸 | [正面](evidence/p0/zombie-front.png)／[背面](evidence/p0/zombie-back.png) | [旁人截图](evidence/p0/zombie-observer.png) | [被自身模型遮挡](evidence/p0/zombie-first-person.png) |
| 骷髅 | [正面](evidence/p0/skeleton-front.png)／[背面](evidence/p0/skeleton-back.png) | [旁人截图](evidence/p0/skeleton-observer.png) | [被自身模型遮挡](evidence/p0/skeleton-first-person.png) |
| 苦力怕 | [正面](evidence/p0/creeper-front.png)／[背面](evidence/p0/creeper-back.png) | [旁人截图](evidence/p0/creeper-observer.png) | [被自身模型遮挡](evidence/p0/creeper-first-person.png) |

早期 F5 测试台周围仍有树叶，造成相机拉近；清除场地障碍后前后视角正常，因此不将早期画面记为独立 F5 缺陷。

结构化证据已保存在本目录的 [evidence/p0](evidence/p0/)：`debug-self-targeting.json`、`debug-end-portal-entry.json`、`debug-first-dragon.json`、`debug-dragon-after-restart.json`、`debug-dragon-attack.json`、`debug-dragon-exit.json`、`debug-end-return.json`。它们记录本次指令、实际返回值与玩家状态，不包含本地 Bridge Token。原始日志在两个隔离服务端的 `logs/`，自动检查日志为 `.local/p0-evidence/checkP0-final.log`，制品和源码校验见 [artifact-manifest.json](evidence/p0/artifact-manifest.json)。

MCPFabric 用于重复操作和读取实际准星／实体状态，GameTest 用于回归。调试客户端含模组，不能替代上面的原版视觉证据。JDWP、spark 已由用户接入；此次定位没有必须新增断点或性能采样的问题，因此没有为了使用全部工具而重复采样。入口见 [调试环境](debug-tools.md)。

本次结束时，早期 `25585` 原型服务端已正常保存并停止，原版客户端已经退出；HRDebug 开发客户端已停止，`25586` 调试服务端保留运行，方便后续通过已接入的 MCP 调试。

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

macOS ARM64 原版客户端：`python3 scripts/p0-client.py prepare` 下载并验证官方文件，`verify` 复核内容，`launch HRProbeA` 或 `launch HRProbeB` 启动独立配置。若需按应用标识绑定 macOS 窗口，先执行 `prepare-app HRProbeA`／`prepare-app HRProbeB`，再执行 `launch-app`；这仅生成原生启动器，仍使用校验一致的官方 JAR。其他操作系统尚无这个测试启动器的支持声明。所有游戏数据保存在 `.local`，没有修改用户既有 Minecraft 安装。

## 硬性阻塞与后续边界

完整尺寸本人替身已在运行时被准星选中，同时遮挡画面。下一步应重做 self-view 技术方案，并使用同一组 C02／C03 验收：服务端攻击重定向只能修复部分攻击语义，不能单独解决遮挡；相机／乘客或现成库方案也必须同时验证第一人称、F5、移动和恢复。当前没有证据证明任何候选已满足全部约束，也不能据此次失败声称所有纯服务端方案均不可能。不得用缩小替身、普通玩家身体或客户端模组降低既定要求。

原型仍沿用玩家物理尺寸，并在装备、死亡、旁观或乘坐变化时安全解除外观；这些都是待实现／待验证范围，不是最终玩法。

世界初始准备目前同步执行，只用于 P0。正式版本须按 tick 分配生成预算，并完成轮换、故障恢复与实体引用释放测试。

首次开发启动曾因 Mixin 配置包范围覆盖入口类而失败；已将伪装和世界 Mixin 独立为各自的 `.mixin` 包配置，之后服务端成功启动。Minecraft 启动失败时 Gradle 仍可能返回成功，因此此次以服务端日志和实际状态为准。
