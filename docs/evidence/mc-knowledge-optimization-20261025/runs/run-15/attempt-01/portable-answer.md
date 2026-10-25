**Java Edition 26.3 原版中，铁傀儡的基础攻击属性是 15；一次近战先随机计算伤害，再按玩家所处难度缩放。** 假定玩家无护甲、无抗性或伤害吸收、不格挡，且本次攻击正常生效：

| 阶段／难度 | 伤害点数（2 点＝1 颗心） | 对应心数 |
|---|---:|---:|
| 基础攻击属性 `attack_damage` | **15** | 7.5 |
| 难度缩放前的随机伤害 | **7.5～21.5** | 3.75～10.75 |
| 简单难度最终伤害 | **4.75～11.75** | 2.375～5.875 |
| 普通难度最终伤害 | **7.5～21.5** | 3.75～10.75 |
| 困难难度最终伤害 | **11.25～32.25** | 5.625～16.125 |

这里的区间包含两端，但取值是离散的。

计算与出处如下：

1. **属性值为 15。** `IronGolem.createAttributes()` 明确设置 `Attributes.ATTACK_DAMAGE = 15.0`；`getAttackDamage()` 读取该属性。属性值本身不代表每次固定造成 15 点伤害。[属性初始化证据](sources/.local/mc-knowledge/evidence/cc3f182b01d8c9de82f4a4e495ef8bac4e9fb614d908523069ca9ee8eef0842c.json)

2. **先随机。** `IronGolem.doHurtTarget()` 使用：
   ```java
   damage = attackDamage / 2.0F + random.nextInt((int)attackDamage);
   ```
   代入 15，即 `7.5 + nextInt(15)`，得到 **7.5、8.5、……、21.5**，共 15 个等概率结果；随后才把这个结果传给目标的 `hurtServer()`。[攻击方法证据](sources/.local/mc-knowledge/evidence/317bd5337dd9536148cd7ffcc794d2e7e56c2a68d6a001268bcf874905be1060.json)

3. **再缩放难度。** 设随机结果为 \(D\)，`Player.hurtServer()` 的公式是：
   - 简单：\(\min(D/2+1,D)\)，本例即 \(D/2+1\)，取值间隔 0.5。
   - 普通：\(D\)，取值间隔 1。
   - 困难：\(3D/2\)，取值间隔 1.5。

   因此不能先把属性 15 按难度缩放后再随机，也不能把简单难度直接算成减半。[玩家难度处理证据](sources/.local/mc-knowledge/evidence/c19bafe450db37e53639729bc4d7d8d3dc4863158734ebdee6425c620192746e.json)

铁傀儡使用 `mob_attack`，其数据指定 `when_caused_by_living_non_player`；`DamageSource.scalesWithDifficulty()` 确认非玩家生物造成的这种伤害适用上述缩放。[伤害类型数据](sources/.local/mc-knowledge/evidence/98859434dc428bc38a67bcc76b27572fd71a42726f5f4f3e1167550912ab482b.json)、[缩放判定代码](sources/.local/mc-knowledge/evidence/f1c9fd6355fbf6dcbe99a42c7076c8fbd398ea98b49665b7385917fcfafa51bf.json)

**版本与确定性：** 官方有 [Java 26.3 正式版发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。本次直接检查版本标识为 **26.3**、与服务端 bundle SHA-256 匹配的原版 JAR，反编译未报告告警；上述数字由代码推导，未启动游戏进行运行时验证。[本地版本及完整性记录](sources/.local/mc-knowledge/evidence/4e79318649e5150fd511f6f1410dab588099a81d4db142ef9983fc078fb47b44.json)

表中的“最终伤害”是本次计算出的伤害量；致死时，玩家实际可扣除的现有生命值仍受剩余生命限制，也不包含击飞后可能发生的摔落伤害。