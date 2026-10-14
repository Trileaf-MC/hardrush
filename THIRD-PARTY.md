# HardRush 第三方组件

HardRush 自身的发布许可证尚未选择。本文件仅记录原型使用的第三方组件，不替这些组件改变授权条件。

| 组件 | P0 版本 | 使用方式 | 上游与许可证 |
| --- | --- | --- | --- |
| Fantasy | `0.8.4+26.3` | 未修改的上游 JAR，嵌入模组 JAR 的 `META-INF/jars/` | [固定版本源代码](https://github.com/NucleoidMC/fantasy/tree/v0.8.4%2B26.3)，[LGPL v3](licenses/Fantasy-LGPL-3.0.txt)及其引用的 [GPL v3](licenses/GPL-3.0.txt) |
| Gradle Wrapper | `9.6.0` | 仓库内启动脚本、Wrapper JAR；按校验值下载构建工具 | [Gradle 源代码](https://github.com/gradle/gradle/tree/v9.6.0)，[Apache 2.0](licenses/Gradle-Apache-2.0.txt) |
| Fabric Loader | `0.19.5` | 服务端外部依赖 | [源代码／Apache 2.0](https://github.com/FabricMC/fabric-loader) |
| Fabric API | `0.162.0+26.3` | 服务端外部依赖 | [源代码／Apache 2.0](https://github.com/FabricMC/fabric) |
| Fabric Loom | `1.17.21` | 仅构建依赖 | [源代码／MIT](https://github.com/FabricMC/fabric-loom) |
| JUnit | `5.14.0` | 仅测试依赖 | [源代码／EPL 2.0](https://github.com/junit-team/junit5) |
| SQLite JDBC | `3.53.4.0` | P1 `persistence` 运行依赖，Loom 添加 Fabric 元数据后以独立 JAR 内嵌，保留原代码、原生库和许可证 | [上游](https://github.com/xerial/sqlite-jdbc)，[Apache 2.0](persistence/licenses/SQLite-jdbc-Apache-2.0.txt)、[Zentus BSD 2-Clause](persistence/licenses/SQLite-jdbc-Zentus-BSD-2-Clause.txt)；原始／嵌套校验见 [P1 证据](docs/evidence/p1-foundations-20261014/manifest.json) |
| MCPFabric | `0.5.1+26.3`；MCP 源码 `f0acc37be526a7c38e52cf1337f3e60e570b4e9d` | 仅独立开发实例与本地 MCP 进程；JAR 未修改 | [固定源码／MIT](https://github.com/denfry/mcpfabric/tree/f0acc37be526a7c38e52cf1337f3e60e570b4e9d) |
| spark | `1.10.187-fabric` | 仅独立开发实例；JAR 未修改；本项目另有开发专用启动兼容 Mixin | [源代码／GPL v3](https://github.com/lucko/spark)，[Modrinth 固定版本](https://modrinth.com/mod/spark/version/e3hsPc1o) |
| MCP TypeScript SDK / Zod / TypeScript | `1.31.0` / `4.6.5` / `7.0.2` | MCPFabric 本地进程及其编译；由 pnpm 锁定，未嵌入 HardRush JAR | [SDK／MIT](https://github.com/modelcontextprotocol/typescript-sdk)，[Zod／MIT](https://github.com/colinhacks/zod)，[TypeScript／Apache 2.0](https://github.com/microsoft/TypeScript) |

Fantasy 的嵌入项保持独立 JAR，不复制或改写其源代码；固定版本源码可从上表取得。构建脚本显式声明该依赖，开发者可以替换为兼容的修改版本并重新构建。正式分发前仍须完成全部依赖和分发材料审查。

Minecraft 客户端、服务端及 JDK 均只保存在忽略的本地测试目录／构建缓存中，不属于 HardRush 发布内容。客户端准备脚本只从 Mojang 官方地址取得文件并验证官方清单校验值；测试须遵守 Minecraft EULA。

HardRush 的 P0 伪装实现为本项目代码，未嵌入 DisguiseLib、Polymer 或其他伪装库代码。

## P0b 基础依赖探针

`probes/p0b` 为独立、仅服务端的核查工程；SQLite JDBC 已在后续 P1 基础中成为运行依赖，SGui 仍仅在该探针工程。三个探针分别使用对应 Fabric API、Fantasy 和 SGui，直接版本在 [versions.json](probes/p0b/versions.json)，实际传递依赖、校验及许可证出处在 [P0b 证据](docs/p0b-dependency-evidence.md)。

| 组件 | 锁定版本 | 使用方式与许可证 |
| --- | --- | --- |
| Fantasy | `0.8.0+26.1.2`／`0.8.3+26.2`／`0.8.4+26.3` | 未修改的独立 JAR 嵌入探针；原 JAR 保留 LGPL v3，探针附 GPL v3；对应 Maven 源码 JAR 的 URL／SHA-256 见证据 |
| SGui | `2.0.0+26.1`／`2.1.0+26.2`／`2.2.1+26.3` | 未修改的独立 JAR 嵌入探针；[LGPL v3](probes/p0b/licenses/SGui-LGPL-3.0.txt)、[源码](https://github.com/Patbox/sgui)；26.1 构建已在 26.1.2 基础探针中运行 |
| SQLite JDBC | `3.53.4.0`，三版相同 | Loom 添加 Fabric 加载元数据后嵌入，原代码／原生库／许可证条目字节保持不变；原始和嵌套 JAR 分别记录校验；[Apache 2.0](probes/p0b/licenses/SQLite-jdbc-Apache-2.0.txt)及保留的 [Zentus BSD 2-Clause](probes/p0b/licenses/SQLite-jdbc-Zentus-BSD-2-Clause.txt)；[上游](https://github.com/xerial/sqlite-jdbc) |

许可证索引区分 POM／父 POM、原 JAR 中的文本／模组声明与固定上游源码快照。部分 Mojang 官方运行库没有在 POM／JAR 声明独立许可证，已明确记录这种情况；这些文件仅存在 Minecraft 构建缓存，未嵌入或随仓库分发。不将该记录当作这些组件可任意重新分发的授权。完整发布组合仍由 P4 审查。

## 本人 Steve 外观资料

2026-10-14 固定的 [Mojang MHF_Steve 公开皮肤描述](https://sessionserver.mojang.com/session/minecraft/profile/c06f89064c8a49119c29ea1dbd1aab82?unsigned=false) 保存于 `platforms/mc263/src/main/resources/hardrush/steve-profile.json`。仅包含公开的纹理描述与来源，不打包 Minecraft 图片、账户凭据或客户端代码；由原版客户端从 `textures.minecraft.net` 加载。本人的临时外观属性无签名，仅限本人的原版本地皮肤路径，真实认证 profile 和其他观察者的数据保持权威来源。不能据此宣称该皮肤图片可以任意重新分发，详见 [实现记录](docs/p0-observer-appearance.md)。
