**Java 26.3 原版铁傀儡的基础攻击属性为 15，但一次近战并非固定造成 15 点伤害。**以下按玩家无护甲、无抗性或吸收效果、未格挡，且该次攻击完整生效计算；1 颗心＝2 点生命值。

| 计算阶段／难度 | 伤害区间（点） | 折合心数 |
|---|---:|---:|
| 难度缩放前的随机伤害 | **7.5～21.5** | 3.75～10.75 |
| 简单最终伤害 | **4.75～11.75** | 2.375～5.875 |
| 普通最终伤害 | **7.5～21.5** | 3.75～10.75 |
| 困难最终伤害 | **11.25～32.25** | 5.625～16.125 |

计算顺序如下：

1. **读取攻击属性。**`IronGolem.createAttributes()` 设置 `ATTACK_DAMAGE = 15.0`；`getAttackDamage()` 读取该属性。[属性与读取代码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-10/attempt-01/workspace/.local/iron-golem-26.3-evidence.txt:31)
2. **先随机。**`IronGolem.doHurtTarget()` 计算：
   ```text
   D = A / 2 + nextInt((int) A)
   ```
   代入 `A = 15`，随机整数取 **0～14**，因此 `D` 是 **7.5、8.5、……、21.5**，共 15 个等概率离散值，并非连续区间。[攻击实现](/private/tmp/hardrush-mc-optimization-20261025/runs/run-10/attempt-01/workspace/.local/iron-golem-26.3-evidence.txt:109)
3. **再按玩家所在难度缩放。**`Player.hurtServer()` 对已经随机出的 `D` 应用：
   ```text
   简单：min(D / 2 + 1, D)
   普通：D
   困难：D × 3 / 2
   ```
   所以三种难度的离散步长分别是 **0.5、1、1.5 点**。难度不会先把属性 15 改写，再进行随机。[玩家难度缩放代码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-10/attempt-01/workspace/.local/iron-golem-26.3-evidence.txt:257)

这次攻击使用 `mob_attack`，其数据规定 `when_caused_by_living_non_player`；铁傀儡符合“非玩家的活实体”条件，因此确实触发上述难度缩放。[伤害类型及判定代码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-10/attempt-01/workspace/.local/iron-golem-26.3-evidence.txt:148) 随后才进入护甲、魔法减伤与吸收处理；题设条件下这些不改变结果。[后续扣血流程](/private/tmp/hardrush-mc-optimization-20261025/runs/run-10/attempt-01/workspace/.local/iron-golem-26.3-evidence.txt:296)

**版本与不确定性：**本次依据本地原版服务端 JAR，`version.json` 标记为 **26.3、stable: true**；核验工具报告其 SHA-256 与服务端 bundle 一致，相关反编译片段无告警。[版本记录](/private/tmp/hardrush-mc-optimization-20261025/runs/run-10/attempt-01/workspace/.local/iron-golem-26.3-evidence.txt:335) 以上是代码推导，未启动游戏实测；不包含击飞后的摔落伤害。表中为伤害量，实际生命值减少最多到玩家死亡为止。