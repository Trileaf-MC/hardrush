**Java 版 26.3：铁傀儡默认攻击伤害属性为 15，但一次近战并非固定造成 15 点伤害。** 以下假定属性未被修改、玩家无护甲、无抗性提升或伤害吸收、未格挡，且这次攻击正常生效；只计算近战本身，不计击飞后的摔落伤害。1 颗心＝2 点伤害。

| 项目 | 伤害点数范围（含端点） | 可能数值的间隔 |
|---|---:|---:|
| 基础攻击属性 `ATTACK_DAMAGE` | **15**，固定属性值 | — |
| 难度缩放前的随机伤害 | **7.5～21.5** | 1 |
| 简单难度最终伤害 | **4.75～11.75** | 0.5 |
| 普通难度最终伤害 | **7.5～21.5** | 1 |
| 困难难度最终伤害 | **11.25～32.25** | 1.5 |

计算顺序是 **读取攻击属性 → 随机化 → 玩家端难度缩放 → 护甲等减伤与伤害吸收 → 扣血**。

1. **属性与随机化。** `IronGolem.createAttributes()` 设置 `ATTACK_DAMAGE = 15.0`；`doHurtTarget()` 先计算：
   ```java
   damage = attackDamage / 2.0F + random.nextInt((int) attackDamage);
   ```
   因而默认情况下 `D = 7.5 + k`，其中 `k ∈ {0,…,14}`，得到 **7.5、8.5、…、21.5** 共 15 个离散值；不会抽到 22.5，也没有把结果取整。[属性证据](sources/.local/mc-knowledge/evidence/54b95fe6384d6c8aecac2f4bd7d4e7ccaa7d5bed79bdc95d1072ba7fc396a9ba.json)、[攻击方法证据](sources/.local/mc-knowledge/evidence/a8e4cc34425be2c3b07368d1ca990144ebb98f445369b6ed85677179bdeac92e.json)

2. **随后按难度缩放。** `Player.hurtServer()` 对已随机得到的 `D` 应用：
   - 简单：`min(D / 2 + 1, D)`；本题范围内就是 `D / 2 + 1`。
   - 普通：保持 `D`。
   - 困难：`D × 3 / 2`。

   因此不能先把属性 15 按难度缩放，再代入随机公式。[玩家难度缩放证据](sources/.local/mc-knowledge/evidence/d9711f6773eec666b8ba18cc1c004566a24d6b1d8a3c558072665c4b6df45485.json)

3. **铁傀儡确实满足缩放条件。** 攻击使用 `mob_attack`，其 JSON 指定 `when_caused_by_living_non_player`；`DamageSource.scalesWithDifficulty()` 判断攻击者为非玩家的活体实体，铁傀儡符合。[伤害类型数据](sources/.local/mc-knowledge/evidence/33093e47fb4dddcee652736e08f3c30cc11a7269a53f5b9064962cd9dd1a143d.json)、[条件判断证据](sources/.local/mc-knowledge/evidence/0f2a4731d4f04f5d464d89731ca4b1501764af47facdca1c97b5794be0220b1b.json)

表中“最终伤害”指计算出的伤害量；若超过玩家剩余生命，实际生命条只能降到零。护甲、魔法减伤和伤害吸收在后续扣血流程处理。[扣血流程证据](sources/.local/mc-knowledge/evidence/5f7c50aea0cadf6b8f9e5e14e958ae3dad95d10e2a4589a6def310213cc1885d.json)

版本方面，官方有 [Java 26.3 正式版发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。本次数值依据本地 **26.3 原版服务端 JAR** 的静态反编译及数据读取；工具报告 JAR 与服务端包 SHA-256 一致，证据文件保留完整哈希与来源。相关方法无反编译告警，但**未启动游戏进行运行时验证**，结论不外推到其他版本或模组环境。