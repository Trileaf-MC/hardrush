# P0：26.3 自身外观的代码证据与验收边界

核验日期：2026-10-13。当前原型使用「隐藏本人玩家模型 + 同坐标、独立 ID 的完整尺寸怪物替身」。这条路径能构造原版实体包，但**尚未通过 C03 第一人称验收**；本文件分别记录源码分析、调试客户端回归和干净原版客户端画面证据。有限场景的通过不代表完整验收通过，当前失败也不证明所有纯服务端方案均不可行。

后续实测补充：两个原版客户端已验证三种形态的本人 F5 和旁人模型；三种第一人称均被自身模型遮挡。MCPFabric 调试客户端读取 `Minecraft.hitResult`，确认依次命中自身 zombie／skeleton／creeper，解除后恢复 `none`。C03 现为明确失败。截图与结构化记录见 [P0 验证记录](p0-validation.md)；本页下面保留源级推理及其适用边界。

**本轮修复尝试：手动隐藏本人替身已恢复基础第一人称操作，但自动配合 F5 的完整方案仍未解决。** 新增 `selfview <player> <hidden|visible>` 探针；三种形态的瞄准、空手攻击、显示切换和一次跨维度恢复已通过客户端回归，双干净原版客户端也完成画面复核。直接替换相机的实验失败：不能正常移动，且准星会选中隐藏的真实玩家。原 C02／C03 标准保持不变，P0 仍未通过。

## 核验来源

直接使用 Java 25 `javap -c -p` 检查本机 Fabric Loom 缓存中的 Minecraft 26.3 原版客户端 JAR；检查方法引用时另加 `-v`。JAR 的 SHA-256 为 `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d`。下列类与方法均来自该版本，不能用旧版 `GameRenderer.pick` 或旧版渲染类型名称替代。

实现入口是 `platforms/mc263/src/main/java/dev/hardrush/p0/disguise/DisguiseProbe.java`。`status` 输出 `selfView`、`selfSpawned`、`selfContainsEye`、`pickableFromInside` 与 `visualAcceptance=pending`。`selfTargeting` 在 visible 时为 `unresolved`，hidden 时为 `self-not-spawned`；后者只说明没有向本人发送替身，不表示完整验收通过。

## 已确认的代码行为

| 问题 | 26.3 类／方法证据 | 对当前原型的含义 |
| --- | --- | --- |
| 客户端从哪里选取实体 | `net.minecraft.client.player.LocalPlayer.raycastHitResult` → `pick` → `net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult`；`EntitySelector.CAN_BE_PICKED` 的方法引用是 `Entity.isPickable` | 选取发生在原版客户端；客户端存在的替身仍参与选取，即使服务器没有注册这个实体 |
| 眼睛处于替身命中盒内部 | `ProjectileUtil.getEntityHitResult(Entity, Vec3, Vec3, AABB, Predicate, double)` 的 `AABB.contains(origin)` 分支：若 `canBePickedFromInside()` 为真，将候选命中距离置为 0 | 完整尺寸替身包含眼睛时可优先命中自己，阻断对前方目标的正常选取；需以实际位置与姿态记录 `selfContainsEye` |
| 普通怪物能否从内部被选取 | `Entity.canBePickedFromInside()` 恒为真；僵尸、骷髅、苦力怕及其相关父类未覆盖它。`LivingEntity.isPickable()` 只检查未被移除 | `NoAI`、无重力、隐身或将生命值设为 0，都不是可靠的「只禁止客户端选取」开关 |
| 把替身挂在玩家身上是否足够 | 同一 `ProjectileUtil` 方法中，`getRootVehicle()` 比较位于不包含射线起点的另一个分支 | 只增加乘客关系不会绕过眼内零距离分支；若依靠乘坐偏移移出眼睛，又需单独证明模型仍正确对齐 |
| 能否通过队伍关闭选取 | 上述选取谓词及眼内命中分支未使用队伍碰撞规则 | 本原型的 `collisionRule=NEVER` 不能解决射线选取问题；它也不改变服务器上的玩家碰撞体 |
| 空手时是否还显示玩家手臂 | `net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.submitArmWithItem` 在主手物品为空且 `AvatarRenderState.isInvisible` 为真时跳过 `renderPlayerArm`；`EntityRenderer.extractRenderState` 从 `Entity.isInvisible()` 提取该状态 | 在本地隐身状态正确生效的前提下，空手玩家手臂应被隐藏。这是渲染条件证据，仍需实际客户端确认 |
| 独立替身是否自动享有第一人称身体隐藏 | `net.minecraft.client.renderer.extract.LevelExtractor.extractVisibleEntities` 只对当前相机实体检查 `Camera.isDetached()` 并跳过其第一人称身体，另有睡眠例外 | 当前相机仍是玩家，独立怪物替身不会因为属于自己而自动被排除 |
| 从怪物模型内部看是否会自动剔除表面 | 目标模型的默认 `EntityModel`／`HumanoidModel` 构造路径使用 `RenderTypes.entityCutout`；对应 `RenderPipelines.ENTITY_CUTOUT` 明确调用 `withCull(false)` | 不能假设「摄像机进入头部，模型背面就不可见」。遮挡范围和角度仍需真实画面确认 |

原版同步实体数据中没有发现适用于这三种实体、只关闭选取而保留完整外观的字段。`SCALE` 会同时改变模型，不满足当前完整尺寸要求。以上是对已检查字段与调用路径的结论，不是对全部包组合的穷尽证明。

## 当前实现的明确边界

- 对其他观察者复用真实玩家 ID 显示原版怪物；本人收到独立 ID／UUID 的替身。替身不加入服务端世界，移动与受伤主体仍是真实玩家。
- 本人仅在客户端收到隐身标志与私有队伍设置；解除时恢复原版玩家元数据和队伍展示。服务器没有据此给玩家添加隐身效果或更改真实队伍。
- 所有装备槽和双手必须为空；已有装备时拒绝启用，启用后装备发生变化则解除。该限制保全物品，但不能据此宣称角色装备、技能物品与 self-view 组合已经完成。
- 没有使用缩小、偏移模型、保留玩家可见身体或安装客户端模组来绕过验收。也没有实现对本人替身的攻击重定向。
- 字节码核验和独立 Java 编译只证明特定代码条件、方法签名及本原型源码可编译；完整 Fabric／Mixin 启动、双客户端外观、动作、重连、跨维度及恢复结果以运行验收记录为准。

## 后续可验证路线

1. **使用已经保留的复现基线比较候选方案。** 三种怪物的前后 F5、第一人称遮挡、眼内命中以及手动切换后的攻击和解除证据已保存。后续方案继续记录包、`status` 与真实画面，避免把生成实体包当作 C03 通过。
2. **对怪物攻击做服务端权威重定向。** 已确定怪物不能挖掘、建造、拾取或打开普通容器，可研究将发往本人替身 ID 的攻击转换为忽略替身的服务端射线，再按距离、视线、角色能力、冷却及原版伤害规则校验。该路线可能恢复怪物攻击语义，不能单独修复客户端准星命中、模型遮挡或持物显示，也不能单独判定 C03 通过。
3. **研究相机／可见实体关系及现成库的完整行为。** 任何相机替换、乘客关系或按视角切换替身的方案，都需同时证明移动、攻击、第一人称、前后 F5、重新连接和传送可用；目前未证明服务端可可靠获知原版玩家的 F5 切换。DisguiseLib 等候选库也必须通过相同验收，不能凭存在 self-view 代码就判断可用。

完整验收标准仍以 `docs/acceptance.md` 的 C01–C03 及相关恢复用例为准；本文件不降低用户要求。

## 2026-10-13：相机实验与手动显示切换

### 研究结论

26.3 的 `LocalPlayer.isControlledCamera()` 要求相机实体与本地玩家是同一个对象；`sendPosition()` 在该条件不成立时不发送通常的移动包。`Minecraft.getCameraEntity()` 直接读取 `mainCamera.entity()`，没有可分别设置的“渲染相机”和“操作相机”。`LocalPlayer.raycastHitResult` 又从相机实体发出射线，因此把相机切到替身后，隐藏的真实玩家成为另一个可选取实体。

实际通过 `ClientboundSetCameraPacket` 将相机切到同坐标僵尸替身后：第一人称身体遮挡消失，但按前进键 20 个客户端 tick，服务端坐标仍为 `(0.5,150,0.5)`；客户端准星命中 `entity.minecraft.player`。这是隔离调试客户端的实际结果，证据见 [客户端运行记录](evidence/self-view-20261013/client-results.json) 和 [相机截图](evidence/self-view-20261013/debug-camera-first-person.png)。单纯替换相机不能满足 C03；没有把该实验加入发布模组。

相机实验命令只存在于 `src/debugTools`：`hardrushdebug camera HRDebug self`／`reset`。最多 100 个服务端 tick 后自动恢复玩家相机，解除伪装、隐藏替身、跨维度或停服时也恢复；断线时移除实验记录。发布 JAR 及嵌套 JAR 检查确认没有该类、GameTest、MCPFabric 或 spark 代码。

原版 `Options.setCameraType` 只写本地字段；F5 的处理没有向服务端报告该状态。`ClientInformation` 包含语言、视距、聊天、皮肤部位、主手、过滤、列表和粒子设置，没有相机视角。因此目前不能按原版 F5 自动决定是否发送替身。此结论针对核查过的原版调用路径；没有证明所有复杂的纯服务端相机／载具组合都不可行。

现成库也不能直接当作满足要求的证据。本轮只阅读上游源码，没有引入或复制其实现：

- [LibsDisguises：Disguise.java](https://github.com/libraryaddict/LibsDisguises/blob/2037e3bbd21daaa18b69ab9edb5f2ce8da7d9408/plugin/src/main/java/me/libraryaddict/disguise/disguisetypes/Disguise.java#L586-L591) 对较高的自身伪装提供缩小或隐藏路径；[self-view 包处理](https://github.com/libraryaddict/LibsDisguises/blob/2037e3bbd21daaa18b69ab9edb5f2ce8da7d9408/plugin/src/main/java/me/libraryaddict/disguise/utilities/packets/packetlisteners/PacketListenerViewSelfDisguise.java#L150-L153) 也会调整自身替身的 SCALE。缩小路线不满足 HardRush 的完整尺寸要求。固定核查提交为 `2037e3bbd21daaa18b69ab9edb5f2ce8da7d9408`，未宣称该 Bukkit 插件兼容本项目 Fabric／26.3。
- [DisguiseLib：实体与 self-view 实现](https://github.com/NucleoidMC/DisguiseLib/blob/1e5ab9356281d436fd16291c9780780c0013d46e/common/src/main/java/xyz/nucleoid/disguiselib/impl/mixin/EntityMixin_Disguise.java) 也使用单独实体 ID 和客户端队伍；本轮没有找到它自动识别原版 F5 并解决上述两项问题的实现。未安装该旧映射版本，也不作 26.3 兼容结论。

### 已实现的替代模式

```text
hardrushp0 disguise HRProbeA zombie
hardrushp0 selfview HRProbeA hidden
# 正常第一人称操作；本人没有替身，其他人仍看到僵尸。
hardrushp0 selfview HRProbeA visible
# 再切 F5，预览完整自身模型。
hardrushp0 selfview HRProbeA hidden
# 切回第一人称前再次隐藏。
hardrushp0 clear HRProbeA
```

命令仍仅 OP 4 可用。`hidden` 只移除本人客户端的替身，保留本地玩家隐藏标志和旁人伪装；隐藏期间不再向本人发送该实体的移动、元数据、摆臂和受伤包。`visible` 重发同一个 ID 的完整模型，重复设置幂等，不缩小、不偏移。跨维度的延迟恢复保留显示选择，避免隐藏状态在传送后重新变成遮挡；解除恢复权威玩家元数据和队伍。默认 `disguise` 仍使用 visible，保留原失败基线。

**这是额外切换显示的实验性替代方案。hidden 状态下按 F5 不会显示自身身体；visible 状态下切回第一人称仍会遮挡和错误选取。** 没有修改验收标准，也没有将这一模式标成最终游戏流程。

### 本轮验证

| 检查 | 实际结果与范围 |
| --- | --- |
| `checkP0` | 9 项 JUnit、3 项 HardRush 服务端 GameTest、1 项框架测试通过；生命周期用例增加手动切换幂等和非法值保护 |
| Fabric 客户端 GameTest | 连接实际 `25586` 服务端，三种 visible 基线均命中自身；hidden 均命中前方猪，普通空手攻击后服务端生命值从 10 降到 9 |
| 模式切换 | 三种形态各 20 次 visible→hidden 循环，最后没有近身替身；只是本模式切换回归，不等同于 C05 的全部生命周期验收 |
| 移动／跨维度／解除 | hidden 下服务端位置确实变化；进入本轮下界后仍为 hidden；clear 后相机为玩家、隐身标志清除、替身不存在 |
| 双干净原版客户端 | HRProbeA／HRProbeB 无模组、无资源包。三种形态均记录 hidden 第一人称、旁人模型，以及手动 visible 后的前／后 F5；另记录苦力怕低头和解除后的正常玩家手臂 |
| 原版普通攻击 | hidden 苦力怕的 HRProbeA 使用正常鼠标输入攻击 HRProbeB，观察到击退；预先建立的 `minecraft.custom:minecraft.damage_dealt` 统计项更新为 10。生命值后来已自然恢复为 20，不能用该迟到的生命值读数判断攻击没有伤害；临时统计项已移除 |
| 发布隔离 | 发布 JAR 和嵌套 JAR 未发现调试相机、测试、MCPFabric 或 spark 代码 |

客户端测试最初写死旧版鼠标按钮编号 0，未触发 26.3 的正确攻击输入；已改为通过 `options.keyAttack` 读取实际按键绑定。场地准备也改为接受“已是目标状态”的原版命令结果，并清理测试目标留下的物品。这些早期失败属于测试修正，最后的通过记录对应当前源码。

原版画面证据（均为 F2 原图，未编辑）：

| 形态 | hidden 第一人称 | 旁人仍见完整怪物 | 手动 visible 后 F5 |
| --- | --- | --- | --- |
| 僵尸 | [第一人称](evidence/self-view-20261013/vanilla-zombie-hidden-first-person.png) | [旁人](evidence/self-view-20261013/vanilla-zombie-observer-while-hidden.png) | [背面](evidence/self-view-20261013/vanilla-zombie-manual-back.png)／[正面](evidence/self-view-20261013/vanilla-zombie-manual-front.png) |
| 骷髅 | [第一人称](evidence/self-view-20261013/vanilla-skeleton-hidden-first-person.png) | [旁人](evidence/self-view-20261013/vanilla-skeleton-observer-while-hidden.png) | [背面](evidence/self-view-20261013/vanilla-skeleton-manual-back.png)／[正面](evidence/self-view-20261013/vanilla-skeleton-manual-front.png) |
| 苦力怕 | [第一人称](evidence/self-view-20261013/vanilla-creeper-hidden-first-person.png) | [旁人](evidence/self-view-20261013/vanilla-creeper-observer-while-hidden.png) | [背面](evidence/self-view-20261013/vanilla-creeper-manual-back.png)／[正面](evidence/self-view-20261013/vanilla-creeper-manual-front.png) |

补充：[原版低头](evidence/self-view-20261013/vanilla-creeper-hidden-look-down.png)、[解除后正常手臂](evidence/self-view-20261013/vanilla-cleared-first-person.png)。源码、制品、日志与证据指纹见 [本轮清单](evidence/self-view-20261013/manifest.json)，原版运行记录见 [服务端日志](evidence/self-view-20261013/vanilla-server.log)。早一轮的 `evidence/p0/artifact-manifest.json` 保留原样，只对应早期制品。

结束时已解除原版客户端伪装、恢复 creative、移除本轮临时统计项和强加载区块，两个原版客户端退出，`25585` 原型服务端正常保存停止。`25586` 调试服务端继续运行，调试客户端未运行。未执行装备／技能、C04 完整移动尺寸、重连持久伪装、反作弊或回放验收；仍只支持空装备探针。
