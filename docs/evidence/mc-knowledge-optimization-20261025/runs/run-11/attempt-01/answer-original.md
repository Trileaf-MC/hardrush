**Java Edition 26.3 原版中，铁傀儡的基础攻击属性是 15；一次近战的最终伤害并不固定为 15。** 以下假设命中可正常受伤的玩家，无护甲、格挡、抗性或吸收效果，也不计击飞后的摔落伤害。单位为伤害点，**2 点＝1 颗心**。

| 计算阶段／难度 | 伤害区间（含端点） | 对应心数 | 相邻可能值间隔 |
|---|---:|---:|---:|
| 随机后、难度缩放前 | **7.5～21.5** | 3.75～10.75 | 1 |
| 简单 | **4.75～11.75** | 2.375～5.875 | 0.5 |
| 普通 | **7.5～21.5** | 3.75～10.75 | 1 |
| 困难 | **11.25～32.25** | 5.625～16.125 | 1.5 |

这些是离散取值，区间内并非任意小数都可能出现。

计算顺序与依据如下：

1. **先读取攻击属性。** `IronGolem.createAttributes()` 设置 `Attributes.ATTACK_DAMAGE = 15.0`。这是后续计算的输入值。[26.3 属性证据](/private/tmp/hardrush-mc-optimization-20261025/runs/run-11/attempt-01/workspace/.local/mc-knowledge/evidence/d8ab750d27360eb131e47686157a12665a4cd86d622f4f0a1f66b4f160d1f297.json)

2. **再随机化。** `IronGolem.doHurtTarget()` 计算：
   ```java
   damage = attackDamage / 2.0F + random.nextInt((int) attackDamage);
   ```
   代入 15，即 `D = 7.5 + k`，其中 `k` 为 0～14 的随机整数，因此得到 15 个可能值：7.5、8.5、……、21.5。[26.3 攻击方法证据](/private/tmp/hardrush-mc-optimization-20261025/runs/run-11/attempt-01/workspace/.local/mc-knowledge/evidence/7bc4af5e1f62aca46ee47532aec4049f8741ef8714b2ed2a41c15fa363355a94.json)

3. **玩家受伤时，才缩放随机结果。** `Player.hurtServer()` 对简单难度使用 `min(D / 2 + 1, D)`，普通保持 `D`，困难使用 `D × 3 / 2`。因此不能先把属性 15 按难度修改，再进行随机抽取。[26.3 难度计算证据](/private/tmp/hardrush-mc-optimization-20261025/runs/run-11/attempt-01/workspace/.local/mc-knowledge/evidence/e6e33378188d45c3f5b28b6f023b1acc9940c9df1363c22822f236826ab3487f.json)

铁傀儡使用的 `mob_attack` 伤害类型，其缩放条件是 `when_caused_by_living_non_player`；铁傀儡符合条件，所以会触发上述玩家难度缩放。[伤害类型数据](/private/tmp/hardrush-mc-optimization-20261025/runs/run-11/attempt-01/workspace/.local/mc-knowledge/evidence/fd555802b5c284890616013d35b35f3ab61414f2880d9e85151c9ad1bd1a8c7a.json)、[条件判定代码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-11/attempt-01/workspace/.local/mc-knowledge/evidence/c813dea0a0af608889496ef30769b757666fe29c89f9b32d872f1880246d29df.json)。扣血使用浮点数，小数不会统一取整；超过玩家剩余生命的伤害会致死，表中上限表示伤害量。[玩家扣血代码](/private/tmp/hardrush-mc-optimization-20261025/runs/run-11/attempt-01/workspace/.local/mc-knowledge/evidence/59ce4e963e148d53681b5155a3a82fd3bb8977c3f5a26defa526b80d204b53f4.json)

版本与不确定性：目标为 [Java 26.3 正式版](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。本地查询确认服务端制品标识为 26.3，且 SHA-256 与服务端包记录一致，完整身份见[校验证据](/private/tmp/hardrush-mc-optimization-20261025/runs/run-11/attempt-01/workspace/.local/mc-knowledge/evidence/3d1ac6bec09a3161cd3f6ddcf5d671a2cde6632cb93192b1acc7d5659af340c2.json)。结论来自静态反编译及关键字节码核对，未启动游戏做运行时测试；上述条件下的数值与计算顺序没有发现歧义。