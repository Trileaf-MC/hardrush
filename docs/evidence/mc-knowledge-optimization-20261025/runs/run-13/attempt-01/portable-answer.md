**Java 26.3 原版铁傀儡的基础攻击属性是 15，但一次近战并非固定造成 15 点伤害。** 对无护甲、无减伤效果、无吸收生命且未格挡的普通玩家，单次完整命中的计算结果如下。1 颗心＝2 点生命值。

| 阶段／难度 | 伤害范围（点） | 折合心数 | 相邻可能值间隔 |
|---|---:|---:|---:|
| 难度缩放前的随机伤害 | **7.5～21.5** | 3.75～10.75 | 1 |
| 简单 | **4.75～11.75** | 2.375～5.875 | 0.5 |
| 普通 | **7.5～21.5** | 3.75～10.75 | 1 |
| 困难 | **11.25～32.25** | 5.625～16.125 | 1.5 |

这些是**离散取值**，每行各有 15 个可能值，并非区间内任意实数。

计算顺序是：

1. **读取攻击属性。** `IronGolem.createAttributes()` 设置 `Attributes.ATTACK_DAMAGE = 15.0`，`getAttackDamage()` 读取该属性。[属性源码证据](sources/.local/mc-knowledge/evidence/e855470a4dac547b6c9f6b53f774fd888ff578b8bb892af2d6958cbdeecd3ce3.json)
2. **先随机。** `IronGolem.doHurtTarget()` 使用：
   ```java
   damage = attackDamage / 2.0F + random.nextInt((int) attackDamage);
   ```
   因此默认情况下，`D = 7.5 + r`，其中整数 `r ∈ {0,…,14}`。`nextInt(15)` 不包含 15，所以最大值是 **21.5**。[攻击方法证据](sources/.local/mc-knowledge/evidence/789ed4bc4639503e26f08458dff6be0d64991f4894eb90d609cbe27fe90fab5f.json)
3. **再按玩家所在难度缩放随机结果。** `Player.hurtServer()` 的公式为：简单 `min(D/2 + 1, D)`，普通保持 `D`，困难 `D×3/2`。这里简单难度全部取 `D/2+1`。[难度缩放源码证据](sources/.local/mc-knowledge/evidence/c62f5e93c0025830f27f3369891e22eea3ca86398e0a61c3d176c212f76b52df.json)

难度缩放适用，是因为该攻击使用 `mob_attack`，其原版 JSON 指定 `scaling: "when_caused_by_living_non_player"`，而铁傀儡符合“非玩家活体攻击者”的条件。[伤害类型数据](sources/.local/mc-knowledge/evidence/189af6b3f7c71aec58a04e0ffa797d06b8bd947f54e8ceca686495b61cf81cf3.json)、[条件判断源码](sources/.local/mc-knowledge/evidence/3dfd49166d2756e595a39aa80693683007798479a9fe858147bc964146decc5f.json)

伤害以浮点数进入扣血流程，**不会先取整到整数或半颗心**。表中是结算伤害；实际减少的生命受剩余生命值限制，且不包含击飞后的坠落伤害。[扣血源码证据](sources/.local/mc-knowledge/evidence/3245fe9f044f32e870049df7f6934c2cbb1fe35c6a56eeb8cb806a7e841b90d5.json)

版本依据是本地标识为 **Java 26.3**、通过服务端 bundle SHA-256 校验的原版 JAR；完整哈希见[校验记录](sources/.local/mc-knowledge/evidence/6707467618c46348a3bf8f62f602f8d2752ae13f804b4c566e17209ca785af57.json)。以上为源码与数据的静态核对，相关片段未报告反编译警告；未启动游戏实测，不外推至其他版本。