**Java Edition 26.3 原版中，铁傀儡的基础攻击属性为 15，但一次近战并非固定造成 15 点伤害。**以下假设玩家无护甲、无抗性提升或伤害吸收、不格挡，且本次攻击正常生效；仅计算近战本身，不含击飞后的摔落伤害。1 颗心＝2 点伤害。

| 阶段／难度 | 伤害点数 | 折合心数 |
|---|---:|---:|
| 基础攻击属性 `ATTACK_DAMAGE` | **15** | 7.5（仅为单位换算） |
| 随机后、难度缩放前 | **7.5～21.5** | 3.75～10.75 |
| 简单最终伤害 | **4.75～11.75** | 2.375～5.875 |
| 普通最终伤害 | **7.5～21.5** | 3.75～10.75 |
| 困难最终伤害 | **11.25～32.25** | 5.625～16.125 |

计算顺序是 **读取攻击属性 → 随机化 → 玩家端难度缩放 → 护甲、效果等后续结算**。

1. `IronGolem.createAttributes()` 设置 `ATTACK_DAMAGE = 15.0`；`doHurtTarget()` 随后计算：
   ```java
   D = A / 2.0F + random.nextInt((int) A);
   ```
   默认 `A=15`，`nextInt(15)` 取整数 **0～14**，所以 `D` 为 **7.5、8.5、……、21.5**，共 15 个离散值，并非连续区间。[属性证据](sources/.local/mc-knowledge/evidence/48547868b68fd0d9f83ef9e560c95fb417ddab7be97682a6bf2990c5b9ef1ec6.json)、[攻击方法证据](sources/.local/mc-knowledge/evidence/417bd489e4aa1280c2f64c63e0b050c3084e3b2664267a0c572d791f6c48e7f6.json)

2. 该攻击使用 `mob_attack`，其 JSON 设置为 `when_caused_by_living_non_player`；铁傀儡符合“非玩家活体实体”，因此触发难度缩放。[伤害类型数据](sources/.local/mc-knowledge/evidence/094dc7de57090540874d180b713e81e508a6ec8ce7aed4e9ce9ab20f3df04b27.json)、[缩放条件代码](sources/.local/mc-knowledge/evidence/316ef3ca251f80b28dd468bd3a1ec77a91e2c6f5bfdda993d3457ee6c7ff9454.json)

3. `Player.hurtServer()` 对**已经随机得到的 D**进行缩放：简单为 `min(D/2+1, D)`，普通保持 `D`，困难为 `D×3/2`。因此简单区间步长为 0.5，普通为 1，困难为 1.5；这里保留浮点数，**没有取整为整数伤害或半颗心**。[玩家受伤代码](sources/.local/mc-knowledge/evidence/37c7c8042428a79a48fed742572374a004e7148c332622ee2b3ca228755c0a3a.json)

版本与证据边界：版本为 [Java 26.3 正式版](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)，本次数值直接核对本地该版本原版服务端 JAR，工具报告其 SHA-256 与服务端 bundle 匹配；上述链接保留了完整哈希、查询参数和反编译片段，相关查询均无截断或反编译告警。结论来自静态代码与数据核查，未启动客户端／服务器作运行时验证。表中伤害可超过玩家剩余生命值，实际生命扣除量仍受剩余生命限制。