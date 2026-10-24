# HardRush

HardRush 是面向原版客户端的多人速通挑战模组。主要特性如下：  

## 榜单和荣誉

模组会统计每一轮的获胜玩家名单和耗时，并在下一轮中给予历届获胜玩家不同等级的荣誉反馈。  

快来竞争成为服务器里史上最快速通之王吧！  

## 亡灵追猎

死亡后的玩家可以化身不同种类的原版怪物追猎未被淘汰的玩家，并通过击杀获得再次作为人类参赛的资格。

友情提醒：初始时你只是一只原版的普通僵尸，但会随着不同的活动变得越来越强！

体验一下成为一只 ～～平平无奇～～（并非）的 MC 僵尸是什么感觉！  

## 构建模组

使用项目自带的 Gradle Wrapper，无需单独安装 Gradle。先准备 Java 25 JDK，并将 `JAVA_HOME` 指向 JDK 安装目录（或确保 `PATH` 中的 `java` 来自 Java 25）。在项目根目录执行：

| 操作 | macOS / Linux | Windows PowerShell / CMD |
| --- | --- | --- |
| 快速构建模组 | `./gradlew assemble` | `.\gradlew.bat assemble` |
| 构建并执行检查 | `./gradlew build` | `.\gradlew.bat build` |

构建完成后会直接显示模组 JAR 的完整路径。产物位于 `platforms/mc263/build/libs/`，文件名随项目版本自动变化。

`assemble` 只打包，不运行测试或启动游戏；`build` 还会执行项目注册的检查，集成测试需要额外的测试环境。首次使用 Wrapper 或缺少依赖缓存时需要联网，重复构建会复用 Gradle 增量缓存。

本机若已有旧开发脚本准备的 JDK，可先在 macOS 当前终端设置一次 `export JAVA_HOME="$(cat .local/toolchains/java-home.txt)"`，随后使用上面的标准命令。`HARDRUSH_JAVA_HOME` 和此本地配置文件仅供旧开发脚本读取，Gradle Wrapper 本身使用标准的 `JAVA_HOME` / `PATH`。

GitHub Actions 的 [构建检查](.github/workflows/build.yml) 会在 macOS 和 Windows 上分别调用标准 Wrapper 构建；验证针对打包流程，不运行游戏或客户端。IntelliJ IDEA 用户也可将 Gradle JVM 设为 Java 25，再在 Gradle 面板中运行 `assemble` 或 `build`。

安装时将生成的 HardRush JAR 和匹配 Minecraft 版本的 Fabric API 放入 Fabric 服务端的 `mods` 目录。完整演示包的构建与启动方式见 [本地演示说明](docs/mvp-demo.md)。
