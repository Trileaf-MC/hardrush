# HardRush 版本与依赖核查

核查日期为 2026 年 10 月 13 日。下表区分“有对应发行物或源码证据”与“已经通过 HardRush 联合验收”。26.3 已进入实际 P0 构建与运行验证；锁定版本、结果及局限见 [P0 验证记录](p0-validation.md)。候选矩阵本身仍不代表联合验收通过，26.1.2 与 26.2 尚未构建。

## 目标版本和工具链

首发目标固定为 26.1.2、26.2、26.3。26.3 官方发布于 2026 年 9 月 15 日；其版本清单要求 Java 25。[26.3 官方公告](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)、[Mojang 版本元数据](https://piston-meta.mojang.com/v1/packages/702fe59163c6ee6578607daa85811d9bc9c7cc40/26.3.json)。

26 系列采用 Java 25。Fabric 对 26.3 发布时建议 Loom 1.17、Gradle 9.6.0、Loader 0.19.5；这些是初始候选，开发开始时需把实际可解析版本及校验锁进构建文件。26.1 的非混淆迁移意味着旧版 Fabric 构建示例不能原样复制。[Fabric 26.3](https://fabricmc.net/2026/09/15/263.html)、[Fabric 26.1](https://fabricmc.net/2026/03/14/261.html)。

## 依赖候选矩阵

表中版本号是核查到的候选，未标“通过”的格子都不能当作兼容承诺。各版本 JAR 由自己的适配器选择，不要求三个目标共用同一二进制文件。

| 组件 | 26.1.2 | 26.2 | 26.3 | 处理方式 |
| --- | --- | --- | --- | --- |
| Fabric API | 0.155.3+26.1.2 | 0.161.0+26.2 | 0.162.0+26.3 | Maven 有发行记录；逐版构建 |
| Fantasy | 0.8.0+26.1.2 | 0.8.3+26.2 | 0.8.4+26.3 | 持久动态世界候选；验证完整三维度流程 |
| SGui | 2.0.0+26.1 候选，需核验 26.1.2 | 2.1.0+26.2 | 2.2.1+26.3 | 可替换的原版容器界面库；缺版本时使用适配器直接实现原版容器 |
| Polymer | 0.16.5+26.1.2 | 0.17.5+26.2 | 0.18.4+26.3 | 虚拟实体构件候选，不据此宣称 self-view 已完成 |
| DisguiseLib | 需锁定或适配源码 | 需锁定或适配源码 | main 源码 1.3.6 | 26.3 源码有 self-view 路径；未锁定可用发布制品 |
| ServerReplay | 3.4.3+26.1，元数据包含 26.1.2 | 3.5.2+26.2 | 3.6.0-beta.5+26.3 | 可选回放集成，另需 Fabric Language Kotlin |
| AntiXray | fabric-1.4.16+26.1 | 同左，发行元数据包含 26.2 | fabric-1.4.16+26.3 | 每轮动态维度保护必须验收 |
| Grim | 2.3.74-f5bbe9c，Alpha | 同左，Alpha | README 声称支持，但本次筛选无匹配发行元数据 | 阻止把尚未验证的构建列为推荐组合 |

发行证据：[Fabric API Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)、[Fantasy Maven](https://maven.nucleoid.xyz/xyz/nucleoid/fantasy/maven-metadata.xml)、[SGui Maven](https://maven.nucleoid.xyz/eu/pb4/sgui/maven-metadata.xml)、[Polymer 发行列表](https://modrinth.com/mod/polymer/versions)。

ServerReplay 对应发行证据：[26.1 候选](https://api.modrinth.com/v2/version/FHOXeRDk)、[26.2 候选](https://api.modrinth.com/v2/version/czoujDEC)、[26.3 候选](https://api.modrinth.com/v2/version/rCmC09FF)。AntiXray：[26.1／26.2 候选](https://api.modrinth.com/v2/version/AK313N9m)、[26.3 候选](https://api.modrinth.com/v2/version/8CvHIf1r)。Grim：[候选发行元数据](https://api.modrinth.com/v2/version/IwwpOU1A)、[2.0 分支说明](https://github.com/GrimAnticheat/Grim/tree/2.0)。

## 自身第三人称的源码证据

DisguiseLib 的 `main` 快照 `5d0b1d295500a9b95babca2f2ffe9ee0f54ce141` 声明构建目标为 26.3，模组版本为 1.3.6。网络处理源码对本人设置隐身元数据，为替身生成独立实体 ID／UUID，并同步位置与朝向。这提供了值得验证的实现方向。[版本配置](https://github.com/NucleoidMC/DisguiseLib/blob/5d0b1d295500a9b95babca2f2ffe9ee0f54ce141/gradle.properties)、[self-view 相关源码](https://github.com/NucleoidMC/DisguiseLib/blob/5d0b1d295500a9b95babca2f2ffe9ee0f54ce141/src/main/java/xyz/nucleoid/disguiselib/impl/mixin/ServerPlayNetworkHandlerMixin_Disguiser.java#L75)。

首次变身的跟踪刷新是否必然向本人生成替身、第一人称是否被遮挡、恢复后是否留下隐身，都仍需实测。客户端本体、可见替身与实际碰撞体也不是天然一致的。该源码只能支持“有候选路径”，不能支持“完整需求已实现”。[变身刷新入口](https://github.com/NucleoidMC/DisguiseLib/blob/5d0b1d295500a9b95babca2f2ffe9ee0f54ce141/src/main/java/xyz/nucleoid/disguiselib/impl/mixin/EntityMixin_Disguise.java#L148)。

Trexler 的 `mc-disguises` 声明支持 self-view，但所核源码 `53f10c4fb311f564efbdb7511951d6cd84ca6e83` 将本人替身缩放为 1/3，且保留本地玩家；不能据此满足完整第三人称变身要求。它可作实现参考，不作为已完成方案。[替身源码](https://github.com/Trexler180/mc-disguises/blob/53f10c4fb311f564efbdb7511951d6cd84ca6e83/src/main/java/com/coffee/disguises/packet/PacketInterceptor.java#L272)、[自身元数据处理](https://github.com/Trexler180/mc-disguises/blob/53f10c4fb311f564efbdb7511951d6cd84ca6e83/src/main/java/com/coffee/disguises/mixin/EntityDataUpdateMixin.java#L312)。

Polymer 支持服务端内容及虚拟实体，是实现工具，并不直接证明 HardRush 的 self-view、受伤和回放组合可用。[Polymer 文档](https://polymer.pb4.eu/latest/)、[虚拟实体说明](https://polymer.pb4.eu/latest/polymer-virtual-entity/basics/)。

## 回放与防作弊的发布边界

ServerReplay 的 README 描述多种录制和回放方式，但 26.3 的 beta.1 发行说明明确暂时移除了 ReplayMod 格式支持；beta.5 的变更说明没有证明该格式恢复。因此必须检查锁定版本源码并实际导出、打开录像，不能在安装文档提前承诺 `.mcpr` 或任一查看器一定可用。[26.3 beta.1 发行说明](https://api.modrinth.com/v2/version/4gMHwqkR)、[beta.5 发行说明](https://api.modrinth.com/v2/version/rCmC09FF)。

AntiXray 支持按自定义维度 ID 配置，默认全局保护未开启。HardRush 每轮新建维度时，必须在区块发送前完成该维度保护；安装模组本身不能替代这一步。[AntiXray 配置说明](https://github.com/DrexHD/AntiXray#config)。

Grim 当前说明覆盖到 26.3，但核查的 Fabric Alpha 发行物元数据只列到 26.2。这不证明 26.3 不能运行，也不足以证明可用于发布。需要锁定可审计构建、核验元数据／源码，并验证伪装实体、末影龙多部位、移动属性和传送。此前任何旧构建的兼容判断不能直接外推到当前构建。[Grim 说明](https://github.com/GrimAnticheat/Grim/tree/2.0)、[当前候选元数据](https://api.modrinth.com/v2/version/IwwpOU1A)。

## 实现前必须产出的锁定结果

第一阶段为每个目标版本产出一份锁定清单：Minecraft、Java、Loader、Loom、Gradle、Fabric API、选用的世界与伪装库、SQLite 驱动、GUI、回放、防作弊及传递依赖的版本、下载来源、校验值、许可证。源码适配另记固定 commit 和本地修改。

第一阶段同时决定最终 self-view 路线、碰撞／受伤模型、回放格式和防护组合。缺失可用现成库时允许实现服务器适配器；不允许用需要客户端模组的库来绕过原版客户端约定。没有通过对应版本的硬性验收，就不发布该版本的正式兼容声明。
