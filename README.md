# HardRush

HardRush 是面向原版客户端的多人速通挑战模组。主要特性如下：  

## 榜单和荣誉

模组会统计每一轮的获胜玩家名单和耗时，并在下一轮中给予历届获胜玩家不同等级的荣誉反馈。  

快来竞争成为服务器里史上最快速通之王吧！  

## 亡灵追猎

死亡后的玩家可以化身不同种类的原版怪物追猎未被淘汰的玩家，并通过击杀获得再次作为人类参赛的资格。

可以选择僵尸、骷髅或苦力怕：僵尸携带成年、幼年随从蛋，骷髅使用弓与三连射，苦力怕用火药“引爆器”按住膨胀、松开消退。僵尸浸水可转为溺尸，骷髅在粉雪中可转为流浪者；怪物可以整理背包和搬运世界物资，出生装备及技能道具绑定本次出场。

具体规则与验证状态见[玩家怪物与原版差异](docs/玩家怪物与原版差异.md)。当前实现已通过自动检查和构建，真实客户端操作与显示仍待[人工验收](docs/人工客户端测试/怪物背包转化与原版机制.md)。玩家随机幼年形态和鸡骑士尚未实现，分别跟踪 [#6](https://github.com/Trileaf-MC/hardrush/issues/6) 和 [#7](https://github.com/Trileaf-MC/hardrush/issues/7)。

体验一下成为一只 ～～平平无奇～～（并非）的 MC 僵尸是什么感觉！  

## 构建模组

使用项目自带的 Gradle Wrapper，无需单独安装 Gradle。先准备 Java 25 JDK，并将 `JAVA_HOME` 指向 JDK 安装目录（或确保 `PATH` 中的 `java` 来自 Java 25）。在项目根目录执行：

| 操作 | macOS / Linux | Windows PowerShell / CMD |
| --- | --- | --- |
| 快速构建模组 | `./gradlew assemble` | `.\gradlew.bat assemble` |
| 构建并执行检查 | `./gradlew build` | `.\gradlew.bat build` |

构建完成后会直接显示模组 JAR 的完整路径。产物位于 `platforms/mc263/build/libs/`，文件名随项目版本自动变化。

`assemble` 只打包，不运行测试或启动游戏；`build` 还会执行项目注册的检查，集成测试需要额外的测试环境。首次使用 Wrapper 或缺少依赖缓存时需要联网，重复构建会复用 Gradle 增量缓存。

本机若已有 `.local/toolchains` 下的 JDK，可先在 macOS 当前终端设置一次 `export JAVA_HOME="$(cat .local/toolchains/java-home.txt)"`，随后使用上面的标准命令。辅助脚本也直接调用 Gradle Wrapper，构建时统一使用标准的 `JAVA_HOME` / `PATH`。

GitHub Actions 的 [构建检查](.github/workflows/build.yml) 会在 macOS 和 Windows 上分别调用标准 Wrapper 构建；验证针对打包流程，不运行游戏或客户端。IntelliJ IDEA 用户也可将 Gradle JVM 设为 Java 25，再在 Gradle 面板中运行 `assemble` 或 `build`。

安装时将生成的 HardRush JAR 和匹配 Minecraft 版本的 Fabric API 放入 Fabric 服务端的 `mods` 目录。本地演示可用 `python3 scripts/demo.py prepare` 创建独立实例，再用 `python3 scripts/demo.py run <实例 UUID>` 启动；操作清单见 [MVP 演示人工检查](docs/人工客户端测试/MVP演示.md)。
