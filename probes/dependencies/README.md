# 基础依赖探针

这是独立 Gradle 工程，用于依赖的提前核查；不包含 HardRush 外观、轮次或玩法适配器，也不接入现有开发工具存档。

使用仓库中同一 Java 25 和 Gradle Wrapper：

```bash
./gradlew -p probes/dependencies checkDependencyProbes --console=plain
```

按 `mc2612 → mc262 → mc263` 顺序在各自 `build/run/gameTest` 中运行无图形界面的真实服务端。每版有 3 项项目测试及 1 项 Fabric 框架测试：Fantasy 服务端钩子加载、SQLite 原生 WAL／迁移回滚／双连接写锁／重开，以及 SGui 原版菜单出站包、点击和关闭入口。网络连接由 EmbeddedChannel 模拟；不能当作实际原版客户端画面、网络登录、完整世界路由或正式 GUI 安全验收。

只复制 `.local/server/eula.txt` 中已有的接受记录，不自动接受 EULA；原始文件未接受时任务会停止。测试数据库是临时文件，不访问开发工具世界或未来正式数据库。探针 JAR 在 `mc*/build/libs/`，只作依赖核查制品。

`versions.json`、三个 `gradle.lockfile` 和 `gradle/verification-metadata.xml` 锁定直接／传递依赖及工具解析的 SHA-256；正常复验不得附带更新锁的参数。有意更新依赖时才使用：

```bash
./gradlew -p probes/dependencies checkDependencyProbes \
  --write-locks --write-verification-metadata sha256 --console=plain
```

自动生成的校验值需重新核对来源和许可证，再运行无更新参数的验证，不能只生成校验文件就宣称来源可信。`dependencyInventory` 导出实际解析图、版本和 JAR 校验到各版 `build/reports/dependencies.json`；其中 `net.minecraft:minecraft-merged-*` 是 Loom 根据官方输入生成的本地文件，不是可下载或可分发的 Maven 组件。

历史核查记录未保存在当前仓库；依赖版本与校验配置可在本工程核对。最终外观路线、26.1.2／26.2 完整原型、回放与防护组合仍按阶段依赖验证。
