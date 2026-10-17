# 26.3 无指令大厅候选包

日期：2026-10-17。仅适用于 Minecraft Java Edition **26.3** 的隔离安装与人工验收。源码、自动验证、候选制品摘要和本次实例 UUID 统一见[第 5 轮交接](lobby-delivery-status.md#第-5-轮交接)。本页不放行整个项目 P0～P4，也不代表 26.1.2／26.2 已正式适配。

## 依赖与制品

| 组件 | 精确版本／交付方式 |
| --- | --- |
| Minecraft Java Edition | 26.3；客户端原版，无必装客户端模组或资源包 |
| Java | 25；本仓库工具链位置由 `.local/toolchains/java-home.txt` 指定 |
| Fabric Loader | 0.19.5 |
| Fabric installer | 1.1.2；锁定启动器及 SHA-256 见 `scripts/demo.lock.json` |
| Fabric API | 0.162.0+26.3；独立放在 `mods` |
| HardRush | 0.1.0-p0+mc26.3；保留现有开发版本号，以本轮 JAR SHA-256 区分构建 |
| Fantasy | 0.8.4+26.3；嵌入 HardRush，不再另装 |
| sqlite-jdbc | 3.53.4.0；嵌入 HardRush |
| 构建 | Gradle 9.6.0、Fabric Loom 1.17.21；准备脚本用 Python 3.11+ |

候选目录是 `.local/demo/bundles/<UUID>`：生产 HardRush、Fabric API、锁定 Fabric 启动器、许可证与 `manifest.json`。清单记录构建基线 HEAD、当时 Git 状态、逐文件源码摘要及制品摘要；HEAD 加未提交工作树摘要共同标识验证源码，不能单用基线 HEAD 指代最终实现。顶层只允许两个模组，审计递归检查嵌入 JAR。Fabric API 自带 GameTest API 是官方运行依赖；本项目 GameTest、MCPFabric、开发探针和 debug-tools 均不进入候选包。历史命名的 `DisguiseProbe` 是正式怪物外观包重写服务，继续保留；被移出的实际探针是 `HardRushP0`、`WorldProbe`、P0 `RoundRuntimeLevel` 和 `WorldManifest`，只由开发模组加载。

## 全新安装与演示

在仓库根目录执行，每次都会创建新 UUID；不覆盖已有存档：

```sh
python3 scripts/demo.py prepare --mode hardcore --port 25587
python3 scripts/demo.py run <输出的极限实例UUID>
```

普通模式另建：

```sh
python3 scripts/demo.py prepare --mode normal --port 25588
python3 scripts/demo.py run <输出的普通实例UUID>
```

默认只监听 `127.0.0.1`，正版认证开启。局域网人工验收可自行显式指定 `--bind 0.0.0.0`，其他电脑连接服务端电脑的局域网地址。首次启动由锁定启动器联网获取原版服务端与运行库。脚本只复制用户已经接受的 EULA；没有既有确认时新实例为 `eula=false`，由用户阅读后确认。

实例是 `.local/demo/instances/<UUID>`，仅启用正式 Runtime 的 `-Dhardrush.p1.enabled=true` 和首次新库模式，不缩短正式冷却、倒计时或追猎预算。`initialMode` 不覆盖已有数据库的模式／下轮规则。前台控制台输入 `stop`，等待 Java 完整退出后才能重开。启动前脚本复核 JAR 和配置，拒绝额外模组或不一致的制品。

已有手工 Fabric 安装可在**新的专用目录**复制相同启动器与 `mods`，使用 Java 25 加 `--enable-native-access=ALL-UNNAMED -Dhardrush.p1.enabled=true -Dhardrush.p1.initialMode=hardcore -jar fabric-server.jar nogui`。不要把开发 classpath、测试模组或 `.local/debug` 的工具一起复制。

## 升级与旧活动轮

1. 在旧构建上正常结束当前比赛，再 `stop` 并确认进程退出及无保存失败。记录旧 JAR SHA-256、完整依赖、模式、当前轮次和规则指纹。先保留完整一致性备份，再在备份副本上试升级；本轮没有修改日常实例。
2. 使用新实例目录和候选制品，迁入同一次停服时保存的完整 `world`、配置、权限、白名单及需要的录制文件。不得混合不同时间点的 SQLite、档案 journal 和维度。`demo.py` 的既有实例有制品锁，升级应新建，不能只替换旧 UUID 的 JAR 以绕过校验。
3. 新轮启用规则 schema 2 的个人定向追猎。schema 1 的历史规则原编码和指纹保持不变。旧活动轮即使 clean stop 也进入 `RECOVERY_REQUIRED`，不会静默改规则、续投票或切到新追猎。优先在旧构建结束旧轮后升级；已经升级而需关闭恢复轮时，按[存储重核与恢复处置](p3-recovery-controls.md)使用有权限的管理员确认／审计流程。
4. 重核成功仍是隔离，不能直接继续不确定活动轮。结束旧轮仍等待档案、录像和原生世界释放；故障资料保留，不用进圈或点击设施绕过。不要修改历史成绩、规则指纹、资格或 ready 位来“修复”。

## 一致性备份与回退

本次数据库 schema 为 5、RoundSnapshot 编码为 5、规则 schema 为 2。旧构建可能无法读新编码、规则或档案，不能承诺任意互读，也不能承诺只回退 JAR 即恢复。升级前保存**对应旧构建的完整一致性备份**和依赖。

最容易复核的备份方式是在正常 `stop`、Java 退出且所有写入者关闭后，复制整个实例目录到一个从未使用的新备份目录。包含 SQLite 主库及仍存在的 `-wal`／`-shm`、`world/hardrush/profiles`、世界拥有权清单、共享大厅及布局标记、全部三维度、原生玩家数据、配置、权限、录像与索引，以及对应 JAR／启动器／许可证。不能只复制运行中的 `p1.sqlite`；若另做在线数据库备份，必须使用 SQLite 一致性备份 API，且它单独不能代表与世界和档案一致。

停服后可按此形式打包，变量均需替换为实际路径，输出文件名必须未存在：

```sh
HARDRUSH_INSTANCE_PARENT=/绝对路径/instances
HARDRUSH_INSTANCE_ID=<实例UUID>
HARDRUSH_BACKUP_FILE=/绝对路径/backups/<实例UUID>-升级前.tar.gz
test ! -e "$HARDRUSH_BACKUP_FILE" && tar -czf "$HARDRUSH_BACKUP_FILE" -C "$HARDRUSH_INSTANCE_PARENT" "$HARDRUSH_INSTANCE_ID"
shasum -a 256 "$HARDRUSH_BACKUP_FILE"
```

保留目录内 `manifest.json`／`demo.json` 对应的 bundle 与摘要，并记录备份时间、轮次和服务端退出日志。恢复演练应在新的目录检查 SQLite `integrity_check`／`foreign_key_check`、档案封套、轮次／身份／规则、世界清单和正确旧构建；本轮没有在用户日常实例执行升级／备份恢复演练。

回退时先停新构建并另存故障现场，然后将升级前**同一备份的全部数据与对应旧构建**恢复到新目录。回退会回到备份时点，升级后产生的成绩与物资另行保留，不能自动合并。新构建写过的库、档案与世界不要交给旧 JAR 继续写。

## 自动复验与人工路线

```sh
python3 scripts/lobby-stabilization.py
python3 scripts/demo.py smoke --timeout 300
```

第一个入口串行启动独立 JVM／新存档，覆盖基础检查、20 连接三轮、完整极限闭环、故障隔离、追猎边界、十轮及 COUNTDOWN／RUNNING／RESCUE／CELEBRATING／ROTATING 正常恢复；可用重复 `--check <名称>` 选择场景。ROTATING 的两 JVM 检查恢复释放后只建唯一下一轮。生产 `smoke` 另建普通／极限存档，以四个新 JVM 只检查准备、正常停服、同轮重开，不创建玩家。每个 runner 保留结果、准确命令、日志和隔离路径；互斥检查拒绝同 JVM 混跑共享大厅专场。

人工从[大厅无指令清单](人工客户端测试/大厅无指令交互与定向追猎.md)第 5 轮路线开始，结合[庆祝清单](人工客户端测试/屠龙庆祝与提前返回大厅.md)。皮肤下载／渲染、准星选取、物品手感、音效、布局通行、真实多人战斗、20 人性能、物理介质故障和录像查看均保持待验。模拟连接、预置竞技地形、低血量首龙及测试时钟不替代这些结论。回放／AntiXray／Grim 的完整集成与多版本发布继续按原项目计划另行验收。


## 本次可用候选与实例

[验证清单](evidence/lobby-stabilization-20261017/validation.json)记录全部命令与限制。候选归档位于 `/Users/leifwebber/projects/minecraft/hardrush/.local/demo/candidates/hardrush-lobby-26.3-c01563b52d41.tar.gz`，SHA-256 `0defd8abde722252fefa215bb980c0f36bf7c052ff38ba5e4283a42dab638e63`；生产 JAR SHA-256 `c01563b52d414ba6be5fd3be1c5c41053eb7fcc2339ab2a62b23508aab088dc6`。

```sh
python3 scripts/demo.py run 22a4936d-7adc-4db4-9b4d-dad8855d8b02
# 极限，127.0.0.1:25587
python3 scripts/demo.py run bbd7b735-a59e-4c53-a1fd-df568214581b
# 普通，127.0.0.1:25588
```

这两份人工实例刚创建，尚未启动；生产 smoke 使用另外两份独立存档。按需要分别启动，并用控制台 stop 正常停止。
