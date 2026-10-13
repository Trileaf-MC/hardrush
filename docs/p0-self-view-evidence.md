# P0：26.3 自身外观的代码证据与验收边界

核验日期：2026-10-13。当前原型使用「隐藏本人玩家模型 + 同坐标、独立 ID 的完整尺寸怪物替身」。这条路径能构造原版实体包，但**尚未通过 C03 第一人称验收**；本文件记录代码层面已经确认的原因与后续路线，不等同于真实画面验收，也不证明所有纯服务端方案均不可行。

## 核验来源

直接使用 Java 25 `javap -c -p` 检查本机 Fabric Loom 缓存中的 Minecraft 26.3 原版客户端 JAR；检查方法引用时另加 `-v`。JAR 的 SHA-256 为 `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d`。下列类与方法均来自该版本，不能用旧版 `GameRenderer.pick` 或旧版渲染类型名称替代。

实现入口是 `platforms/mc263/src/main/java/dev/hardrush/p0/disguise/DisguiseProbe.java`。`status` 输出 `selfContainsEye`、`pickableFromInside`、`selfTargeting=unresolved` 与 `visualAcceptance=pending`，便于将协议构造和视觉结果分开记录。

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

1. **先保留当前原型复现基线。** 分别对三种怪物记录前后 F5、第一人称上下转向、眼内命中、攻击、解除后的身体与手臂。既记录包与 `status`，也记录真实画面，避免把生成实体包当作 C03 通过。
2. **对怪物攻击做服务端权威重定向。** 已确定怪物不能挖掘、建造、拾取或打开普通容器，可研究将发往本人替身 ID 的攻击转换为忽略替身的服务端射线，再按距离、视线、角色能力、冷却及原版伤害规则校验。该路线可能恢复怪物攻击语义，不能单独修复客户端准星命中、模型遮挡或持物显示，也不能单独判定 C03 通过。
3. **研究相机／可见实体关系及现成库的完整行为。** 任何相机替换、乘客关系或按视角切换替身的方案，都需同时证明移动、攻击、第一人称、前后 F5、重新连接和传送可用；目前未证明服务端可可靠获知原版玩家的 F5 切换。DisguiseLib 等候选库也必须通过相同验收，不能凭存在 self-view 代码就判断可用。

完整验收标准仍以 `docs/acceptance.md` 的 C01–C03 及相关恢复用例为准；本文件不降低用户要求。
