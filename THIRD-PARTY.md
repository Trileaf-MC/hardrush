# HardRush P0 第三方组件

HardRush 自身的发布许可证尚未选择。本文件仅记录原型使用的第三方组件，不替这些组件改变授权条件。

| 组件 | P0 版本 | 使用方式 | 上游与许可证 |
| --- | --- | --- | --- |
| Fantasy | `0.8.4+26.3` | 未修改的上游 JAR，嵌入模组 JAR 的 `META-INF/jars/` | [固定版本源代码](https://github.com/NucleoidMC/fantasy/tree/v0.8.4%2B26.3)，[LGPL v3](licenses/Fantasy-LGPL-3.0.txt)及其引用的 [GPL v3](licenses/GPL-3.0.txt) |
| Gradle Wrapper | `9.6.0` | 仓库内启动脚本、Wrapper JAR；按校验值下载构建工具 | [Gradle 源代码](https://github.com/gradle/gradle/tree/v9.6.0)，[Apache 2.0](licenses/Gradle-Apache-2.0.txt) |
| Fabric Loader | `0.19.5` | 服务端外部依赖 | [源代码／Apache 2.0](https://github.com/FabricMC/fabric-loader) |
| Fabric API | `0.162.0+26.3` | 服务端外部依赖 | [源代码／Apache 2.0](https://github.com/FabricMC/fabric) |
| Fabric Loom | `1.17.21` | 仅构建依赖 | [源代码／MIT](https://github.com/FabricMC/fabric-loom) |
| JUnit | `5.14.0` | 仅测试依赖 | [源代码／EPL 2.0](https://github.com/junit-team/junit5) |
| MCPFabric | `0.5.1+26.3`；MCP 源码 `f0acc37be526a7c38e52cf1337f3e60e570b4e9d` | 仅独立开发实例与本地 MCP 进程；JAR 未修改 | [固定源码／MIT](https://github.com/denfry/mcpfabric/tree/f0acc37be526a7c38e52cf1337f3e60e570b4e9d) |
| spark | `1.10.187-fabric` | 仅独立开发实例；JAR 未修改；本项目另有开发专用启动兼容 Mixin | [源代码／GPL v3](https://github.com/lucko/spark)，[Modrinth 固定版本](https://modrinth.com/mod/spark/version/e3hsPc1o) |
| MCP TypeScript SDK / Zod / TypeScript | `1.31.0` / `4.6.5` / `7.0.2` | MCPFabric 本地进程及其编译；由 pnpm 锁定，未嵌入 HardRush JAR | [SDK／MIT](https://github.com/modelcontextprotocol/typescript-sdk)，[Zod／MIT](https://github.com/colinhacks/zod)，[TypeScript／Apache 2.0](https://github.com/microsoft/TypeScript) |

Fantasy 的嵌入项保持独立 JAR，不复制或改写其源代码；固定版本源码可从上表取得。构建脚本显式声明该依赖，开发者可以替换为兼容的修改版本并重新构建。正式分发前仍须完成全部依赖和分发材料审查。

Minecraft 客户端、服务端及 JDK 均只保存在忽略的本地测试目录／构建缓存中，不属于 HardRush 发布内容。客户端准备脚本只从 Mojang 官方地址取得文件并验证官方清单校验值；测试须遵守 Minecraft EULA。

HardRush 的 P0 伪装实现为本项目代码，未嵌入 DisguiseLib、Polymer 或其他伪装库代码。
