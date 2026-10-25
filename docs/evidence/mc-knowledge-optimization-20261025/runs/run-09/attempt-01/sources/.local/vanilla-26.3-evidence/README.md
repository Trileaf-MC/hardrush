# 本次原版机制调查的证据索引

目标：Minecraft Java Edition 26.3 正式版。仅静态读取原版服务端 JAR、Vineflower 1.12.0 反编译结果和 javap 字节码；没有启动游戏客户端或服务端，没有读取既有调查报告、聊天历史、个人记忆或其他实验目录。

## 版本与输入

- [JAR 内 version.json](version.json)：id/name 为 26.3，stable 为 true，构建时间 2026-09-15T11:20:48+00:00。
- [doctor 原始输出](doctor.txt)：提取服务端 JAR 的 SHA-256 与服务端 bundle 内清单匹配。
- 输入 JAR SHA-256：`a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6`。
- [完整本地来源、源文件哈希及字节码复查参数](provenance.json)。源码文件直接复制自本次 CLI 返回的缓存路径，没有手工改写。
- [官方 26.3 发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)：版本身份辅助核实，不用发布说明替代具体机制代码。

## 骷髅转化

- [Skeleton.java](Skeleton.java)：初始化参数 `isInPowderSnow`、`FreezingTime=140`、`StrayConversionTime=300`；isShaking 与转化状态一致；canFreeze 返回 false。
- [Skeleton 构造器字节码](Skeleton-constructor-bytecode.txt)：复核 140、300 及字段初始化。
- [ConversionTracker.java](ConversionTracker.java)：tick、startConversion、doConversion、存档读取/写入的完整实现。
- [ConversionTracker.tick 字节码](ConversionTracker-tick-bytecode.txt)：复核倒计时减到负数才完成，以及不再处于细雪时设置 afflictionTime=-1 并取消转化。
- [InsideBlockEffectType.java](InsideBlockEffectType.java)、[PowderSnowBlock.entityInside](PowderSnowBlock-entityInside.java)：细雪方块效果设置 isInPowderSnow；该状态设置不受 canFreeze 返回值阻挡。
- [ConversionType.java](ConversionType.java)：SINGLE 转化复制装备、装备掉落概率、载具关系、状态效果等；并不代表所有字段都原样继承。

计时推导（静态推导）：标称参数为 140+300 tick，20 TPS 下约 22 秒。conversionTime 从 300 开始，301 次后续有效 tick 才变为 -1。afflictionTime 初值 0 时需要 140 次有效 tick；在雪外经过重置成为 -1 后需要 141 次。因此从跟踪器首次观测到 isInPowderSnow=true 起，分别需要 441 或 442 次连续有效更新。实体接触方块与跟踪器取样的先后还会影响从外部观测的入雪时刻；不保证物理接触后恰好第 440 tick 转化。

## 狼与流浪者

- [Wolf.registerGoals](Wolf-registerGoals.java)、[相应字节码](Wolf-registerGoals-bytecode.txt)：优先级 7 为普通 NearestAttackableTargetGoal，目标类型 AbstractSkeleton；没有未驯服限定。
- [Stray.java](Stray.java)：Stray extends AbstractSkeleton。
- [NonTameRandomTargetGoal.java](NonTameRandomTargetGoal.java)：未驯服限定在此类；狼捕猎动物和小海龟使用它，骷髅类选敌未使用它。
- [TamableAnimal.setTame](TamableAnimal-setTame.java)、[Wolf.applyTamingSideEffects](Wolf-applyTamingSideEffects.java)：驯服标记及副作用不会移除上述骷髅类选敌规则。
- [NearestAttackableTargetGoal.java](NearestAttackableTargetGoal.java)、[TargetingConditions.test](TargetingConditions-test.java)：目标获取有随机检查及距离、视线、敌我等判断，不能把规则存在说成一定立即咬到目标。
- [SitWhenOrderedToGoal.java](SitWhenOrderedToGoal.java)：坐下占用 MOVE/JUMP，Wolf.registerGoals 中优先级 2 高于近战优先级 5。
- [Wolf.hurtServer](Wolf-hurtServer.java)：受伤流程解除坐下命令，因此坐下不是所有情形下的永久不攻击保证。
- [OwnerHurtByTargetGoal.canUse](OwnerHurtByTargetGoal-canUse.java)、[OwnerHurtTargetGoal.canUse](OwnerHurtTargetGoal-canUse.java)、[Wolf.wantsToAttack](Wolf-wantsToAttack.java)：已驯服且未被命令坐下的狼另有保护主人和攻击主人目标的规则。

## 最小复查命令

在仓库根目录运行，沿用 MC_KNOWLEDGE_FORMAT，不传格式覆盖参数：

```sh
python3 scripts/mc-knowledge.py --version 26.3 doctor
python3 scripts/mc-knowledge.py --version 26.3 --max-lines 200 --max-chars 12000 source net.minecraft.world.entity.monster.skeleton.Skeleton
python3 scripts/mc-knowledge.py --version 26.3 --max-lines 200 --max-chars 12000 source net.minecraft.world.entity.ConversionTracker
python3 scripts/mc-knowledge.py --version 26.3 source net.minecraft.world.entity.ConversionTracker --member tick --bytecode
python3 scripts/mc-knowledge.py --version 26.3 source net.minecraft.world.entity.animal.wolf.Wolf --member registerGoals
python3 scripts/mc-knowledge.py --version 26.3 source net.minecraft.world.entity.monster.skeleton.Stray
python3 scripts/mc-knowledge.py --version 26.3 source net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal
```

不确定性：以上结论限定此哈希对应的 Java 26.3 原版实现；未做运行时试验，不推定其他版本、基岩版、模组或服务器插件相同。秒数假设 20 TPS、实体持续获得 tick、存活且 AI 未被禁用。
