# 冻结事实基准独立审查

审查日期：2026-10-25。范围：仅 `standard_answer.md`、`rubric.json`、`evidence_text/`，以及相同 Java 26.3 原版服务端 JAR 的补充静态源码／字节码查询。没有读取 `/tmp/hardrush-mc-experiment-20261025/` 下任何内容，没有启动游戏，没有进行浏览器或真实客户端验证。按项目 `minecraft-knowledge` 技能工作。

## 结论

**未发现足以推翻必答标准或修改分值的事实错误。** 五个重点结论均得到对应版本直接证据支持：驯服狼保留骷髅类主动选敌；正常稳定坐下会阻止普通追逐攻击；离开细雪会取消已开始转化；精确调用边界是 140/141 + 301；NoAI 暂停跟踪器处理而不主动清零。

**发现一项可能实质影响 W4 裁决的未展开例外：狼自身受伤可取消坐下命令。** 这不是必答标准的反例，因为标准限定“正常稳定坐下”，但裁决时必须区分狼受伤与主人受伤，详见下文。其余边界已经由标准正确限定，勿把限定读丢。

冻结检查：`shasum -a 256 -c frozen_checksums.sha256` 对 `standard_answer.md`、`rubric.json`、`evidence_manifest.json` 均为 OK。没有修改这三份冻结文件或既有证据；补充查询只新增缓存，本文件为审查输出。

## 关键事实逐项核验

| 判断 | 核验结果与直接依据 |
| --- | --- |
| 两阶段时间 | `skeleton.txt:20–22` 配置 140 与 300；`conversion_tracker.txt:53–71` 先递增 affliction，再在达到阈值时启动 conversion。约 7 + 15 = 22 秒的名义说明正确。 |
| 第二阶段离开取消 | `conversion_tracker.txt:55–69` 的细雪 false 分支在 `isConverting()` 分支外，同时设 afflictionTime=-1 和 converting=false。因此两个阶段都清除／取消；再次进入先重新累计，旧 conversionTime 虽留在字段中但新阶段启动会覆盖为 300。字节码 116–123 偏移亦一致。 |
| 精确 tick 边界 | afflictionTime 初值 0，第一次连续 true 达阈值需 140 次；此前 false 重置到 -1 后需 141 次。启动调用只设 conversionTime=300，同次不减；之后 300 次减至 0，第 301 次减到 -1 才完成。总计 441/442 次**合格 true 跟踪器调用**正确。`Skeleton.tick()` 先跟踪后 super，`Entity.baseTick()` 清理细雪标记，所以这不等于从几何进入瞬间起的绝对世界 tick 计数。 |
| NoAI | `conversion_tracker.txt:54` 将全部 true/false 分支都放在服务端、存活、非 NoAI 守卫内。NoAI 不推进，也不执行离开重置。仅说“停止推进”正确；说“NoAI 立即清除进度”错误。 |
| 冻结与转化 | `skeleton.txt:63–64` 的 canFreeze=false；`inside_block_effect.txt` 仍无条件设置细雪标志，只对 canFreeze 实体增加 TicksFrozen；`living_ai_step.txt` 冻伤伤害再次要求 canFreeze。普通冻伤驱动转化的解释错误。 |
| 野生与驯服狼 | `wolf_register_goals.txt:22` 对 AbstractSkeleton 注册普通 NearestAttackableTargetGoal，`stray.txt:21` 证明 Stray 继承 AbstractSkeleton。该目标不检查驯服状态；`non_tame_target.txt` 的 !isTame 只属于另一类目标。TamableAnimal.canAttack (`tamable.txt:179–180`) 只排除主人后继续父类检查，并未对驯服狼禁用骷髅类目标。补查 Wolf 全部成员及 applyTamingSideEffects，没有发现驯服移除目标的路径。 |
| 护主与坐下 | 两个 OwnerHurt*Goal.canUse 都明确要求 isTame && !isOrderedToSit。`sit_goal.txt:13` 占 JUMP/MOVE，优先级 2；普通 MeleeAttackGoal 为优先级 5，占 MOVE/LOOK。补查 GoalSelector.tick 与 WrappedGoal.canBeReplacedBy：冲突标志下只有更高优先级可替换。故正常稳定坐下阻止普通近战／扑击，但独立 targetSelector 仍可选择骷髅目标。 |

## 会影响裁决的解释边界

### 1. 狼自身受伤取消坐下命令：应接受准确例外，不能与护主混同

补查相同 JAR 的 `Wolf.hurtServer(ServerLevel, DamageSource, float)`，完整方法无截断、无反编译告警：

```java
if (this.isInvulnerableTo(level, source)) {
    return false;
}
this.setOrderedToSit(false);
return super.hurtServer(level, source, damage);
```

因此免疫检查通过的狼自身受伤流程会取消 orderedToSit；不必等 OwnerHurt*Goal 允许启动。随后坐下目标的 canContinueToUse 不再成立，自卫目标与正常行动可能恢复。这不保证任何受伤场景都成功反击，但足以证明“坐下狼自身受攻击后可能起身自卫”是合理且有直接源码支持的说明。

冻结标准的可选“自卫不同于护主”段只讲 HurtByTargetGoal 和行动互斥，没有展开这条取消命令路径，属于**不完整说明而非核心错误**。W4 已限定“正常稳定坐下”，无需修改冻结分值；裁决时应把以下两类断言分开：

- “主人受伤时，被命令坐下的狼一定自动起身护主”：错误，OwnerHurt*Goal 明确不允许启动。
- “狼自己被攻击后可能起身自卫，因为受伤代码取消坐下命令”：正确，不应当作直接否认 W4 而扣分。

相反，“坐下狼遇到任何情况都绝不反击／永远不取消命令”不能由冻结标准支持。

补充源码缓存：`cache/artifacts/a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6/sources/2556d7f32cd32931dc89a1fbaa8493962a60c6415fcfd12976e942d15377093b/source.java`，方法视图第 15–25 行。复核：

```sh
python3 scripts/mc-knowledge.py --version 26.3 --cache-dir /tmp/hardrush-mc-reference-20261025/cache source Wolf --member hurtServer --max-lines 120 --max-chars 14000
```

### 2. 约数、精确调用数和真实经过时间不能混算

冻结规则已经正确接受“约 440 tick／约 22 秒”。不得因未主动讨论 1–2 tick 实现边界扣这类概述的时间分。相反，若明确声称“普遍精确 440 tick”，才触发精确性规则。441/442 也只在说明初始 affliction 状态及连续合格 true 调用时精确；NoAI、死亡、卸载、细雪标志时序和 TPS 使墙钟时间不能直接等同这些数字。S2 的通常概述与 optional 深挖应按其现有区别执行。

### 3. 主动选敌存在，不代表任何驯服狼都立即攻击

W2 支持的是普通可行动条件下的自主骷髅类目标规则。命令坐下、其他行为占用、目标合法性／队伍、可见性、距离和选敌随机调度都可能阻碍实际攻击；标准已经指出这些条件。对“驯服狼站立且正常行动时仍自主攻击流浪者”的肯定应给分；若只写“所有驯服狼无条件攻击”，不应从标准推导保证。目标选择与动作执行不能互相替代。

## 审查限制

本审查为静态原版核验，不能证明运行时路径、视觉颤抖、特定地形追逐或 HardRush 覆盖后行为。没有根据任何实验答案改变或重写标准。以上裁决边界基于原版证据独立提出。
