# T2 冻结标准答案

题目：Minecraft Java26.3中，铁傀儡对普通无护甲玩家一次近战的基础攻击属性、实际随机伤害区间，以及简单/普通/困难难度下的最终区间分别是什么？区分属性值与最终伤害并解释随机/难度缩放顺序。

适用条件：Java 26.3 原版服务端；普通生存玩家、无护甲、无抗性等额外减伤或吸收、攻击成功且不在免伤窗口；铁傀儡攻击属性未经修改。单位为伤害点（2点=1颗心）。这里的“最终伤害”指本次命中的伤害量；玩家剩余生命扣至0后不能变成负数，因此不把20血玩家实际可损失的血量误当成攻击上限。

基础 ATTACK_DAMAGE 属性为 **15.0**。IronGolem.getAttackDamage 将该属性转为 float，doHurtTarget 先执行：

```java
float damage = attackDamage / 2.0F + random.nextInt((int)attackDamage);
```

15对应 `7.5 + k`，k为0至14的整数，故随机基础伤害是 **{7.5,8.5,…,21.5}**，上下界均可取，共15个离散值，间隔1点；不是15固定伤害、不是7至21整数，也不是连续区间或包含22.5。

随后 mob_attack 伤害源进入玩家 Player.hurtServer。它的 scaling 为 when_caused_by_living_non_player，铁傀儡满足条件；随机抽样在前，玩家按难度缩放在后：

| 难度 | 对已抽得伤害d的运算 | 最终可取值 | 闭区间 |
| --- | --- | --- | --- |
| 简单 | min(d/2+1,d)；本题d均大于2，等于d/2+1 | 4.75 + 0.5k，k=0…14 | **[4.75,11.75]** |
| 普通 | d不变 | 7.5+k，k=0…14 | **[7.5,21.5]** |
| 困难 | d×3/2 | 11.25+1.5k，k=0…14 | **[11.25,32.25]** |

均是float伤害；Player.actuallyHurt在无额外防御条件下用float直接扣血，没有把伤害向上/向下取整。方法里 `Math.round(dmg*10)` 只记录统计，不能据此把伤害取整。无护甲使护甲公式倍率为1；若有药水、吸收或其他状态，就要另算。

证据链（全部来自同一26.3服务端JAR）：
- raw/golem_attributes.json：IronGolem.createAttributes，ATTACK_DAMAGE=15.0。
- raw/golem_getattack.json、raw/golem_attack.json及raw/golem_attack_bytecode.json：读取float属性、除2后加nextInt，再传给hurtServer；字节码fdiv/i2f/fadd确认无整数除法。
- raw/bit_nextint.json：BitRandomSource.nextInt(I)的界限为0至bound-1。
- raw/mob_attack_data.json、raw/scales_difficulty.json、raw/player_hurt.json及raw/player_hurt_bytecode.json：伤害源缩放条件及玩家难度公式。
- raw/player_actual.json、raw/combat_armor.json、raw/player_magic.json、raw/living_sethealth.json：float扣血、零护甲倍率1，生命值下限0。

精简证据与具体成员行号见 key-evidence.md；原始命令见queries.jsonl；版本/JAR身份见provenance.json。仅静态查询，未运行游戏。
