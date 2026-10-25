**Java 版 26.3 原版铁傀儡的基础攻击属性是 15，但一次近战并非固定造成 15 点伤害。**先随机生成 **7.5～21.5 点**伤害，再按受击玩家所在世界的难度缩放。

以下假设玩家无护甲、无抗性或伤害吸收效果、未格挡，这次攻击完整命中；只计算近战本身，不含击飞后的摔落伤害。单位均为生命值点，**2 点＝1 颗心**。

| 计算阶段／难度 | 公式 | 伤害区间（含端点） |
|---|---|---|
| 难度缩放前 | `D = 15 / 2 + nextInt(15)` | **7.5～21.5** |
| 简单 | `min(D / 2 + 1, D)` | **4.75～11.75** |
| 普通 | `D` | **7.5～21.5** |
| 困难 | `D × 1.5` | **11.25～32.25** |

具体顺序与出处如下：

1. **读取属性，再随机。**`IronGolem.createAttributes()` 将 `ATTACK_DAMAGE` 设为 `15.0`；`doHurtTarget()` 读取该属性，计算 `属性 / 2 + nextInt((int)属性)`。`nextInt(15)` 取整数 **0～14**，因此原始伤害是 **7.5、8.5、……、21.5**，共 15 个等概率离散值，并非区间内任意小数。见 [属性初始化](/private/tmp/hardrush-mc-optimization-20261025/runs/run-14/attempt-01/workspace/.local/iron-golem-26.3-evidence/IronGolem.createAttributes.java:12)、[攻击计算及调用顺序](/private/tmp/hardrush-mc-optimization-20261025/runs/run-14/attempt-01/workspace/.local/iron-golem-26.3-evidence/IronGolem.doHurtTarget.java:19)。

2. **将随机结果交给玩家，随后缩放难度。**该攻击使用 `mob_attack`，其[原版 JSON](/private/tmp/hardrush-mc-optimization-20261025/runs/run-14/attempt-01/workspace/.local/iron-golem-26.3-evidence/mob_attack.json:4)指定由非玩家生物造成时随难度缩放；铁傀儡满足这一条件。玩家的 [`Player.hurtServer()`](/private/tmp/hardrush-mc-optimization-20261025/runs/run-14/attempt-01/workspace/.local/iron-golem-26.3-evidence/Player.hurtServer.java:40)执行表中的简单／困难公式，普通难度保持原值。**难度不会先把基础属性 15 改掉，再拿去随机。**

3. **之后进入减伤与扣血流程。**[`Player.actuallyHurt()`](/private/tmp/hardrush-mc-optimization-20261025/runs/run-14/attempt-01/workspace/.local/iron-golem-26.3-evidence/Player.actuallyHurt.java:25)依次处理护甲、魔法减伤和伤害吸收；在上述条件下，表中结果就是最终伤害量。超过玩家剩余生命值的伤害会致死，不能理解为血条还能实际减少超过剩余生命值的数值。

版本与不确定性：调查对象是 **26.3 正式版**，对应[官方发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。本地 JAR 内的 [`version.json`](/private/tmp/hardrush-mc-optimization-20261025/runs/run-14/attempt-01/workspace/.local/iron-golem-26.3-evidence/version.json:2)标记为 `26.3`、`stable: true`，且[校验记录](/private/tmp/hardrush-mc-optimization-20261025/runs/run-14/attempt-01/workspace/.local/iron-golem-26.3-evidence/doctor.txt)显示其 SHA-256 与服务端包内记录一致。结论来自该版本原版数据和反编译代码，相关方法无反编译告警；未启动游戏进行运行时验证，也未以其他版本或模组行为替代。