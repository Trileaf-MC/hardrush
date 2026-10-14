# P0b 三版本基础依赖与接口核查

2026-10-14，按本轮要求暂不处理 B01，完成可提前进行的 P0b-01／P0b-07。使用独立 [探针工程](../probes/p0b/README.md)，没有移植外观或世界路由，也没有实现正式赛制／数据库／大厅。P0、P0b 阶段验收仍未通过。

## 锁定范围

| 项目 | 26.1.2 | 26.2 | 26.3 |
| --- | --- | --- | --- |
| Java | Temurin 25.0.4.1+1，macOS ARM64 | 同左 | 同左 |
| Gradle／Loom／Loader | 9.6.0／1.17.21／0.19.5 | 同左 | 同左 |
| Fabric API | 0.155.3+26.1.2 | 0.161.0+26.2 | 0.162.0+26.3 |
| Fantasy | 0.8.0+26.1.2 | 0.8.3+26.2 | 0.8.4+26.3 |
| SGui | 2.0.0+26.1 | 2.1.0+26.2 | 2.2.1+26.3 |
| SQLite JDBC | 3.53.4.0 | 3.53.4.0 | 3.53.4.0 |
| 自有外观实现 | 同一 P0 源码来源，尚未移植／通过 B01 | 同左 | 保留已有 P0 原型，未改变方案 |

精确版本由 [versions.json](../probes/p0b/versions.json) 和 [工具属性](../probes/p0b/gradle.properties) 固定。三版 `gradle.lockfile` 锁定实际解析版本；[严格依赖校验](../probes/p0b/gradle/verification-metadata.xml)覆盖下载的 JAR、POM 和 Gradle 模块元数据，包括 Loom 构建图。冻结后不加任何更新参数的构建／服务端测试已通过。

归档的三份依赖清单包含运行、GameTest、Minecraft 库、Loader 库和 Loom 构建依赖；合计 285 个非生成 Maven 坐标，每个实际 JAR／原生分类制品分别记录 SHA-256 和配置用途。清单同时包含 Loom 为编译解析的客户端库，不能把它们误写成需要玩家安装的依赖。`net.minecraft:minecraft-merged-*` 属于 Loom 本地生成物，记录其校验及官方输入，不伪造远端 Maven 下载链接。

Minecraft 官方客户端／服务端输入已分别核对官方元数据的 SHA-1、大小并补记 SHA-256；Temurin 压缩包 SHA-256 与上游校验文本一致，Gradle 使用仓库已有的官方发行校验值。游戏和 JDK 二进制仍只在忽略的本地目录／缓存中，证据目录只归档文本清单和日志。

基础运行库来源：[Fabric Maven](https://maven.fabricmc.net/)、[Nucleoid Maven](https://maven.nucleoid.xyz/)、[Maven Central](https://repo.maven.apache.org/maven2/)。Fantasy／SGui／SQLite 的固定源码 JAR URL 和 SHA-256 见 [sources.json](evidence/p0b-dependencies-20261014/sources.json)。本项目外观代码的文件及指纹见 [manifest.json](evidence/p0b-dependencies-20261014/manifest.json)，无外部伪装库嵌入；最终可行路线仍由 B01 决定。

## 实际操作与结果

在仓库根目录使用已有 Java 25 执行：

```bash
# 首次生成锁和校验；只有有意更新依赖时才再次使用这两个参数。
scripts/p0-gradle.sh -p probes/p0b checkP0bDependencies \
  --write-locks --write-verification-metadata sha256 --console=plain

# 正常复验：不更新锁和校验。
scripts/p0-gradle.sh -p probes/p0b checkP0bDependencies --console=plain
```

三版分别启动真实 Fabric 服务端，按顺序完成 3 项项目测试＋1 项 Fabric 框架测试，共 12 项。初次冻结后的复跑通过；补充出站包检查后最终运行也通过。主验收日志为 [最终测试](evidence/p0b-dependencies-20261014/check-final.txt)，之前的 [冻结构建](evidence/p0b-dependencies-20261014/check-bootstrap.txt)和 [首次锁定复跑](evidence/p0b-dependencies-20261014/check-locked.txt)保留为过程记录，不当作最终新增包检查的证据。补齐许可证后的 [制品构建](evidence/p0b-dependencies-20261014/build-artifacts.txt)只重建探针 JAR，未改测试逻辑。

| 测试 | 实际断言 | 范围 |
| --- | --- | --- |
| `fantasyServerHooksLoad` | `Fantasy.get(server)` 有效，真实服务端 Mixin 已初始化 | 基础加载；不替代完整三维度／龙战适配 |
| `sqliteNativeMigrationRollbackAndWriterLock` | WAL 返回 `wal`；成功迁移及 schema 版本写入；故意失败后 DDL／版本一起回滚；并发读不见未提交数据，第二连接写入返回 SQLITE_BUSY，提交后可写；关闭重开保留值，完整性检查 `ok` | 真正加载 sqlite-jdbc 的本机原生库；临时测试数据库，非正式 schema |
| `vanillaContainerOpensClicksAndCloses` | 打开 `GENERIC_9x3`；记录实际发出的原版打开屏幕／容器内容包，无自定义载荷；已开菜单不能重复打开；原版网络点击包触发一次回调、图标不可取走且背包保留；关闭包恢复玩家菜单 | GameTest 玩家＋EmbeddedChannel 模拟连接，SGui 原版网络处理 Mixin 真实执行 |

SGui 的点击回调位于 `ServerGamePacketListenerImpl.handleContainerClick` 注入点；直接调用菜单的 `clicked` 不会覆盖这条入口。探针最终使用原版 `ServerboundContainerClickPacket`／`ServerboundContainerClosePacket`，避免把容器对象操作误当成网络 GUI 测试。

另在忽略目录创建只有 sqlite-jdbc 的临时 Gradle 工程，复制校验元数据并将驱动的预期 SHA-256 改成全零。严格解析如预期拒绝该 JAR，确认校验会阻止不匹配制品；主工程和探针的锁／校验文件均未改动。操作夹具和失败诊断见 [verification-negative](evidence/p0b-dependencies-20261014/verification-negative/build.gradle)、[校验拒绝日志](evidence/p0b-dependencies-20261014/verification-negative.txt)。此失败是有意的负向测试。

## 许可证与制品隔离

完整 [许可证索引](evidence/p0b-dependencies-20261014/licenses.json)区分 POM／父 POM 中的声明、原 JAR 内的许可证文本／模组元数据，以及固定上游源码快照。部分 Mojang 官方库没有独立许可证声明，单独注明仅随 Minecraft 运行且未分发，不推测其可重新分发授权。该基础锁定不是 P4 的最终组合分发审查。

SGui 和 Fantasy 原 JAR 保持独立、未修改并保留 LGPL v3 文本；探针外层另附 GPL v3。sqlite-jdbc 是普通 Java 库，Loom 在嵌入时添加 `fabric.mod.json`，原始与嵌套 JAR 校验分别记录；逐条比较确认原代码、原生库、Apache 2.0 和 Zentus BSD 文本均保持原字节。本轮新增许可文件只位于 `probes/p0b/licenses`；现有 P0 运行依赖和构建工程未增加 SQLite／SGui。详情见 [第三方组件](../THIRD-PARTY.md)。

逐版探针 JAR 只包含服务端探针元数据、许可证和 Fantasy／SGui／SQLite 三个嵌套 JAR。GameTest 类、P0 游戏源码、MCPFabric、spark 与 debugTools 均不在其中。测试类只进入独立 GameTest source set；未向玩家分发任何客户端内容。

## 证据与边界

[manifest.json](evidence/p0b-dependencies-20261014/manifest.json)记录基线 commit、未提交源码、三版探针 JAR／嵌套依赖／测试 class、依赖锁／校验、工具链、配置和日志指纹。已有 P0 世界／末地证据保持原样，未改成新探针的验收记录。

- 没有启动真实浏览器或 Minecraft 图形客户端；不声明原版客户端实际连接／画面通过。
- SQLite 只在本机 macOS ARM64、Java 25 和每版 Fabric 类加载环境验证；双 JDBC 连接证明本次锁冲突，不替代跨进程、崩溃恢复或其他 OS 测试。
- 没有验证正式异步持久化、真实 schema 迁移、主线程回调、重复／迟到请求或玩法 GUI；这些在 P1／P2 继续实现。
- 没有移植自有伪装、动态三维度／首龙、回放、防矿透或防作弊；P0b-02～06／08／G 继续未完成。
- 构建中 sqlite-jdbc 的四段发行版本会触发 Loom 的 semver 提示；探针按原始 Maven 版本锁定，构建和实际类加载成功，嵌套包装差异已审计。GameTest 模拟玩家入口的弃用提示不改变目标三版的本轮结果。
- 未提交、push、发布或部署。
