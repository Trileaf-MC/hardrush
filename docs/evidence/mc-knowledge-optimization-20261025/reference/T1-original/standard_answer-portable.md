# Java 26.3 原版机制：独立事实评分基准

范围：Minecraft Java 26.3 原版服务端。仅静态核对本机官方 JAR 及其反编译／字节码；未运行游戏，未读取实验答案，不代表 HardRush 覆盖后的行为。基准创建日期 2026-10-25。

服务端 JAR：`/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar`。
SHA-256：`a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6`；CLI doctor 已核对服务端 bundle 的 SHA-256。反编译器 Vineflower 1.12.0。见 `evidence/doctor.json`。

## 必答标准答案

### 骷髅与细雪

1. 普通骷髅 Skeleton 持续处于细雪条件下会转为流浪者 Stray，分两个阶段：先累计细雪时间，阈值 140 tick（20 TPS 下约 7 秒）；再进入颤抖／转化阶段，设置 300 tick 倒计时（约 15 秒），通常概述为约 22 秒。颤抖依据 `freezingTracker.isConverting()`，不是通用 `TicksFrozen` 的“完全冻结”。来源：`Skeleton` 字段 `freezingTracker`、`isShaking`、`ConversionTracker.tick/startConversion`。
2. 本版本两个阶段都要求细雪条件持续成立。条件变为 false 时，`afflictionTime=-1` 并且 `setConverting(false)`：第一阶段进度清除，已开始的颤抖／转化也取消；重新进入需要重新经历第一阶段，不是暂停续算。尤其不能套用旧版“颤抖后离开也会完成”的说法。来源：`ConversionTracker.tick`。
3. 跟踪器只在服务端、实体存活且 `!isNoAi()` 时工作。`NoAI` 会停止跟踪器推进，守卫外没有清零操作，所以应说暂停处理，不能说 NoAI 本身触发重置。转化本身不会设置 NoAI 或禁用 AI；不应把颤抖理解成丧失全部行动能力。来源：`Skeleton.tick`、`ConversionTracker.tick`。
4. 转化计时与普通冻伤分离：`Skeleton.canFreeze()` 明确返回 false，所以不能把它说成先受到普通细雪冻伤再转化。普通 FREEZE 方块效果仍设置 `isInPowderSnow`，但只对 `canFreeze()` 的实体增加 `TicksFrozen`；普通冻伤伤害同样要求 `canFreeze()`。流浪者也在 `freeze_immune_entity_types` 标签中。免疫普通冻结不等于免疫细雪的碰撞／陷入／物理减速。来源：`Skeleton.canFreeze`、`InsideBlockEffectType.FREEZE`、`LivingEntity.aiStep`、`PowderSnowBlock.entityInside`、实体类型标签。

### 狼、流浪者与主人

5. 野生狼会主动把流浪者作为猎杀目标，不要求先被流浪者或主人攻击。目标规则匹配 `AbstractSkeleton`，`Stray extends AbstractSkeleton`；不是仅匹配普通 `Skeleton`。这仍受一般选敌、距离、调度、可攻击性和行动条件制约，不保证任何距离下立即攻击成功。来源：`Wolf.registerGoals`、`Stray`、`NearestAttackableTargetGoal`、`TargetGoal`。
6. 驯服并不会移除这条骷髅类主动选敌规则，正常能够行动的驯服狼也可自主攻击流浪者。羊／兔／狐狸、陆地幼龟等自主捕猎则走 `NonTameRandomTargetGoal`，它启动时检查未驯服；不能把这项限制套到骷髅类目标。来源：`Wolf.registerGoals`、`NonTameRandomTargetGoal.canUse`。
7. 主人受攻击与主人攻击目标，是两条独立的协助路线：`OwnerHurtByTargetGoal` 和 `OwnerHurtTargetGoal`，优先级分别 1、2，早于骷髅类主动选敌的 7。两者均要求已驯服且未被命令坐下，并经过目标合法性与 `wantsToAttack` 等条件，不能泛称“主人附近所有敌对生物都会被主动攻击”。来源：两类 `canUse/start`、`Wolf.registerGoals/wantsToAttack`。
8. 被命令坐下时，两个主人协助目标都不能启动；正常坐下目标优先级 2，占用 MOVE/JUMP，阻止低优先级扑击、近战追逐。因此普通稳定坐下状态不会像站立狼一样主动追杀流浪者或协助主人。骷髅类目标选择规则自身没有坐下判定，选到目标与实际追逐攻击应区分。坐下不是无敌，也不是移除了全部 AI：浮水、惊慌等更高优先级或特殊状态不能用“永远绝不反应”概括。来源：`OwnerHurtByTargetGoal`、`OwnerHurtTargetGoal`、`SitWhenOrderedToGoal`、`Wolf.registerGoals`。

## 可选深挖，不是满分必需条件

- **精确 tick 边界**：跟踪器的 `afflictionTime` 构造初值为 0；从此前未经历 false 条件且初值 0 开始，连续 140 次合格 true 调用启动转化。从已经执行过 false 分支的 -1 开始，要 141 次 true 调用才达到 140。启动时 `conversionTime=300`，同次调用不减；之后第 300 次合格调用减到 0，下一次第 301 次才减到 -1 并完成。因此简化跟踪器调用序列为 441 或 442 次合格 true 调用，而不是普遍精确 440 次。`Skeleton.tick` 在 `super.tick` 前执行跟踪器，而 `Entity.baseTick` 清理细雪标记、随后方块效果重设标记，所以不能把跟踪器调用数直接当作从几何进入细雪那一瞬间算起的精确世界 tick。字节码证据见 `conversion_tick_bytecode.txt`。
- **NoAI 边界**：NoAI 期间连细雪条件 false 的重置分支也不会执行；恢复 AI 后才重新判定。卸载、不再 tick 等情况也不按现实秒数累计。常规秒数须以 20 TPS 持续模拟为前提。
- **普通冻结对比**：`Entity.getTicksRequiredToFreeze()` 为 140；可冻结实体的 FREEZE 效果累计 TicksFrozen，离开或不能冻结时在 `LivingEntity.aiStep` 每次减 2；完全冻结且 `tickCount % 40 == 0 && canFreeze()` 时触发基本 1 点 freeze 伤害。该机制不是 Skeleton 的 ConversionTracker。`tryAddFrost` 还有基于冻结比例的移动速度修饰；不能将其与细雪方块的 stuck movement 混为一谈。
- **坐下的精确语义**：`orderedToSit` 与 `isInSittingPose` 为不同状态。`SitWhenOrderedToGoal.canUse` 有在水中、不在地面、主人缺席／异维度，以及主人近于 12 格且有 lastHurtByMob 等条件；这些条件影响坐下目标能否启动。已运行时 `canContinueToUse` 只返回 orderedToSit。不能根据“主人在附近受伤使 canUse=false”便推导已经稳定坐下的狼一定起身保护主人：两个主人目标仍明确检查 `!isOrderedToSit()`。主人交互切换坐下还会停止导航并清空当前 target，见 `Wolf.mobInteract`。
- **自卫不同于护主**：`HurtByTargetGoal` 是独立目标，不能由护主目标被禁用推断完全没有自卫选敌；实际追击还受坐下目标和更高优先级行为控制。
- **保护主人的例外**：`OwnerHurtByTargetGoal` 检查最近 100 tick 的伤害来源及 `NO_WOLF_RETALIATION`（本 JAR 标签值为 sulfur_cube_hot）；`wantsToAttack` 排除苦力怕、恶魂、盔甲架及若干驯服／PvP 目标。无需背诵这些例外取得必答分，但泛称“任何目标都攻击”错误。
- `AbstractSkeleton.registerGoals` 有 6 格避狼目标，可用于解释追逐行为；不保证特定场景的实际路径。无需回答流浪者缓慢箭时长、装备迁移、存档字段等旁支取得满分。

## 证据与复核

所有原始 CLI JSON 位于 `evidence/`，保留来源、JAR 哈希、反编译缓存位置、截断状态和返回范围；对应实际文本位于 `evidence_text/`。`evidence_manifest.json` 列出文件 SHA-256。文本摘录的行号是所保存摘录行号，不冒充 Mojang 原始源码行号。`wolf.json`、`tamable.json` 为明确截断的类视图；本基准依赖的狼 registerGoals、wantsToAttack、mobInteract 和相关 Goal 均有独立未截断证据。两个失败的继承成员查询也原样保留，不作为结论证据。

关键证据：

- `evidence_text/skeleton.txt`：类全文（86 行），转化参数、tick、颤抖和 canFreeze。
- `evidence_text/conversion_tracker.txt`：第 53—71 行 tick 守卫、倒计时和取消逻辑；第 109—111 行启动。
- `evidence_text/conversion_tick_bytecode.txt`：tick 字节码交叉核对 `>=140`、`<0` 和 false 分支。
- `evidence_text/wolf_register_goals.txt`：第 4—10 行坐下与行动优先级；第 16—22 行目标优先级和普通／未驯服专属目标。
- `evidence_text/stray.txt`、`non_tame_target.txt`：继承关系与未驯服判定。
- `evidence_text/owner_hurt_by.txt`、`owner_hurt_target.txt`、`sit_goal.txt`、`sit_goal_bytecode.txt`：护主启动条件与坐下维持逻辑。
- `evidence_text/inside_block_effect.txt`、`living_ai_step.txt`、`freeze_immune.txt`：冻结机制分离。

复核命令（仓库根目录；指定独立缓存，不启动游戏）：

```sh
python3 scripts/mc-knowledge.py --version 26.3 --cache-dir /Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/reference/cache doctor
python3 scripts/mc-knowledge.py --version 26.3 --cache-dir /Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/reference/cache source Skeleton
python3 scripts/mc-knowledge.py --version 26.3 --cache-dir /Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/reference/cache source ConversionTracker --member tick --bytecode
python3 scripts/mc-knowledge.py --version 26.3 --cache-dir /Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/reference/cache source Wolf --member registerGoals
python3 scripts/mc-knowledge.py --version 26.3 --cache-dir /Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/reference/cache source OwnerHurtByTargetGoal --member canUse
python3 scripts/mc-knowledge.py --version 26.3 --cache-dir /Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/reference/cache source SitWhenOrderedToGoal
```

评分以 `rubric.json` 为准。事实正确性 90 分，证据可复核性 10 分分别报告；可选深挖单独记录，不因篇幅更长加分。若可选断言与必答事实直接冲突，该事实不得分；其他可选错误记录但不改变冻结后的总分量表。盲评前不得按实验答案调整本基准。
