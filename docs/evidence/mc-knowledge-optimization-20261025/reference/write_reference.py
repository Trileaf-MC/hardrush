from pathlib import Path
import json,hashlib,datetime
r=Path('/tmp/hardrush-mc-optimization-20261025/reference')
(r/'T2-answer.md').write_text('''# T2 冻结标准答案

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
''')
(r/'T2-rubric.md').write_text('''# T2 冻结量表（100分：事实90＋证据10）

只按题目明确要求评分，同义表达及等价公式可得分。未问的攻击频率、击飞、生命值、和平难度等不作为额外扣分项。不要重复处罚同一错误；每项分数下限0。以下可按子项给部分分。

| 项目 | 分值 | 满分条件／拆分 |
| --- | ---: | --- |
| 基础属性与伤害的区别 | 12 | 属性15.0（8）；明确它不是固定命中伤害（4） |
| 随机伤害公式与区间 | 24 | 15/2+nextInt(15)或等价7.5+k（8）；范围7.5至21.5且端点可取（8）；k为0…14，15个间隔1的离散float值，不能当连续或整数取整（8） |
| 简单难度 | 14 | 缩放min(d/2+1,d)或本题有效的d/2+1（6）；最终4.75…11.75（8） |
| 普通难度 | 10 | 不缩放（3）；最终7.5…21.5（7） |
| 困难难度 | 14 | ×1.5（6）；最终11.25…32.25（8） |
| 计算顺序和玩家处理 | 16 | 先抽随机再由玩家缩放（8）；非玩家生物造成的mob_attack适用玩家难度缩放（4）；无护甲等通常条件下保留float直接作用于玩家生命，无伤害整数舍入（4） |
| 证据 | 10 | 版本明确Java26.3、原版及来源可定位（2）；属性＋攻击公式引用具体源码成员（3）；mob_attack缩放条件＋Player.hurtServer可核对（3）；出处与实际查询相符、保留关键限制/未把静态推导称运行验证（2） |

解释规则：区间写法配合“离散、两端均可取”即可，不强制写全部15个值；无需写每种难度心数。不强制说明20血封顶、统计round或护甲完整公式，但不能用这些把本题伤害错误改成整数或20上限。引用不强制采用基准文件名，只要目标版本、类/方法或数据路径可复核；未指定必须字节码，不因只用可靠源码扣分。
''')
(r/'T3-answer.md').write_text('''# T3 冻结标准答案

题目：Java26.3的刷怪笼在玩家离开后是否继续刷怪？给出默认玩家激活距离、刷怪延迟、一次尝试数量、附近同类上限、计时/生成失败时的行为，区分普通刷怪笼与试炼刷怪笼。

## 普通刷怪笼（BaseSpawner）

**最后一名满足条件的玩家离开默认激活范围后，不继续生成，剩余spawnDelay也暂停，不会仅因离开/回来自动重新随机。** 前提是对应方块和区块正常被服务端tick且刷怪笼功能开启；区块停止tick同样不能继续执行。其他玩家仍在范围内则保持激活。

- RequiredPlayerRange默认 **16**。从方块中心(x+0.5,y+0.5,z+0.5)到活着且非旁观玩家的三维距离严格小于16，即距离平方<256；创造模式也可激活普通笼。
- 初始/缺省Delay为 **20 tick**。MinSpawnDelay=**200**，MaxSpawnDelay=**800**；reset公式200+nextInt(600)，因此通常重设后的值为整数 **200…799 tick（两端可取）**。常见“10–40秒”是20TPS近似称法，800是排除上界，不能严格说可抽得800。
- 激活时，Delay=-1先调用delay重设；Delay>0只减1，不在从1减到0的同一tick刷怪；原已为0（或其他非正且非-1）则进入尝试。离开范围时整个这些分支都不执行。
- SpawnCount默认 **4**，即一轮至多4次生成尝试；不是保证4只，也不是每tick永久4只。
- MaxNearbyEntities默认 **6**。达到或超过6时调用delay后立即结束本轮；检查的是待生成实体的**精确Java类**，默认在刷怪笼方块1×1×1的AABB向四周膨胀SpawnRange=4，得到9×9×9的盒内计数，并排除旁观实体。它不是全世界自然刷怪上限。
- 失败分类影响计时：碰撞不允许、生成位置/亮度等规则不满足、Mob自身规则或阻塞检查不通过，跳过本次尝试；若整轮没有成功且没有遇到下述重设条件，Delay仍为0，下一激活tick会重试，**不会因任何失败都等200…799 tick**。只要本轮至少一次成功，结束时重设delay。实体类型无法解析、加载结果为null、附近同类达到上限、或添加实体/乘客失败，均会立即delay并return；不是零延迟连续重试。

对应证据：raw/spawner_ctor.json、raw/spawner_load.json、raw/spawner_near.json、raw/near_alive.json、raw/spawner_tick.json、raw/spawner_delay.json、raw/bit_nextint.json。

## 试炼刷怪笼（TrialSpawner）

不能套普通笼“16格外就暂停”的结论。默认探测玩家范围是 **14**；第一次登记玩家需要视线，之后可登记范围内更多玩家。已进入ACTIVE且已登记玩家后，在方块继续被tick且世界允许生成时，玩家走出范围本身不清空detectedPlayers、不暂停，也不阻止继续生成这一轮。仍受本轮总量和同时存活的受追踪怪物数量限制，结束后进入奖励/冷却等状态。无人仍在tick的区块不能被说成会后台继续执行。

它使用nextMobSpawnsAt等游戏时间戳和状态机，不使用普通笼的Delay/SpawnCount/MaxNearbyEntities。纯默认配置为：检测新玩家后的至少40 tick缓冲；生成间隔ticksBetweenSpawn=40；每次就绪调用一次spawnMob；每名首玩家的本轮总量6、同时上限2，额外登记玩家默认增加总量2、同时上限1（两类目标值用floor），实际结构/普通与不祥配置可覆盖这些值，故不是统一附近6只就停。默认冷却target_cooldown_length=36000 tick；这不是普通笼200…799随机间隔。

生成成功才在状态机中递增totalMobsSpawned、登记currentMobs并把nextMobSpawnsAt设置为当前时间+间隔；spawnMob返回empty（位置碰撞、规则、加载、添加等失败）不会执行该成功分支、不会仅因该次失败重设间隔，下个就绪tick可重试。世界禁止试炼生成时（例如和平、相应游戏规则禁止）会resetStatistics并从ACTIVE回到WAITING_FOR_PLAYERS；这与单纯玩家离开不同。追踪怪物被移除还会把下次生成时间推至当前时间+间隔。

对应证据：raw/trial_fullconfig_tail.json、raw/playerdetector.json、raw/trial_detect.json、raw/trial_state_tick.json、raw/trial_ready.json、raw/trial_config_values.json及tail、raw/trial_spawn.json、raw/trial_tick.json、raw/trial_can_spawn.json、raw/trial_reset_stats.json。

适用版本Java26.3原版服务端JAR，静态源码/字节码查询；版本与成员行号见provenance.json和key-evidence.md。HardRush可通过Mixin或配置改变原版结论；本题不评估模组效果。
''')
(r/'T3-rubric.md').write_text('''# T3 冻结量表（100分：事实90＋证据10）

核心数字默认针对普通刷怪笼；试炼部分要求明确区别，不强迫穷尽试炼全部配置、战利品和不祥机制。以下附带精确细节是评分边界澄清，不把未问细节增加为额外必答条件。允许等价表达，按子项给部分分，每项分数下限0，同一错误不重复扣。

| 项目 | 分值 | 满分条件／拆分 |
| --- | ---: | --- |
| 普通笼玩家离开和计时 | 18 | 无满足条件玩家则不生成（6）；Delay暂停且保留，不因离开/回来重抽（8）；玩家距离/区块tick前提足以避免笼统说仍后台生成（4） |
| 默认激活范围 | 8 | 16格（6）；活玩家、非旁观、围绕方块中心三维范围等基本限定不误导（2） |
| 默认延迟及推进 | 16 | 初始20 tick（3）；Min200/Max800（4）；实际抽样200…799或明确[200,800)（5）；正值递减、归零后尝试和-1重设能正确说明（4） |
| 尝试次数 | 8 | 一轮最多4次尝试（5），非保证4只且成功/失败会影响实际数量（3） |
| 附近同类限制 | 10 | 默认6，已>=6会停止该轮并重设（7）；局部同类计数，不能说成全局自然刷怪上限（3） |
| 普通生成失败的关键区别 | 16 | 普通位置/碰撞/生成规则失败且整轮无成功时保持零延迟，下个激活tick重试（7）；至少成功一次结束后重设（4）；附近上限和无效/加载/添加失败等存在立即重设并返回的分支，避免“所有失败一律不重设/一律重设”（5） |
| 试炼笼区别 | 14 | 默认14格探测（3）；已激活登记后走远本身不暂停，受tick/配置与状态轮次限制（6）；状态机/时间戳及受追踪怪物/本轮配额机制，不能套普通的4次、附近6及200…799延迟（3）；失败不会执行成功后重设nextMobSpawnsAt的分支，可再尝试（2） |
| 证据 | 10 | 明确Java26.3原版及来源身份（2）；普通BaseSpawner默认字段/初始化、serverTick与delay的可核对出处（4）；试炼状态机/探测或计时的可核对出处（3）；没有把截断或推导冒充完整/游戏实测（1） |

边界：普通激活恰好16格的严格不等号、计数盒9×9×9、精确Java类、试炼默认40/6/2/+2/+1/36000及视线细节可用于判断回答自相矛盾，但**不因未主动列出这些未明确追问的次级细节另扣分**；其中试炼“14格”单列为已明确询问的默认激活距离差异。普通刷怪延迟常见写200–800，只要明确800是排除上界可满分；写可抽得800时该抽样子项不得满分。无需列出每一个早退失败原因，只要涵盖普通失败可重试、成功重设、上限或加载/添加异常重设的区别即可。
''')
files=['golem_attributes','golem_getattack','golem_attack','golem_attack_bytecode','bit_nextint','mob_attack_data','scales_difficulty','player_hurt','player_actual','living_sethealth','spawner_ctor','spawner_load','spawner_near','near_alive','spawner_tick','spawner_delay','trial_fullconfig_tail','trial_detect','trial_state_tick','trial_ready','trial_config_values','trial_spawn','trial_tick','trial_can_spawn','trial_reset_stats']
parts=['# 精简关键证据（原文；行号为CLI缓存成员视图行号）\n\n所有输出和完整出处元数据保存在raw/；原始查询在queries.jsonl。下列text保留相关成员完整正文，避免截断关键分支。\n']
for n in files:
 d=json.loads((r/'raw'/f'{n}.json').read_text()); src=d.get('decompiled',{}); cl=src.get('class',d.get('class_name',d.get('resource',''))); member=src.get('member',d.get('member',{})); rr=d.get('returned_range',{})
 txt=d.get('text','')
 if n=='trial_config_values':
  txt='// 摘录：原视图第59–65及108–116行\n'+'\n'.join(txt.splitlines()[58:65]+txt.splitlines()[107:116])+'\n'
 parts.append(f'\n## {n}\n\n出处：{cl} / {member}；returned_range={rr}；原输出truncated={d.get("truncated")}。\n\n```java\n{txt}```\n')
(r/'key-evidence.md').write_text(''.join(parts))
d=json.loads((r/'raw/doctor.json').read_text())
provenance={'frozen_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'questions':['T2','T3'],'independence':'未读取任何实验答案；仅冻结原版CLI、静态JAR数据/反编译/字节码；未启动游戏/浏览器；未写仓库','model_requested':'GPT-6.1 Sol high（继承父任务，不切换模型）','source':d['source'],'jdk':d['jdk'],'vineflower':d['vineflower'],'baseline_cli':'/tmp/hardrush-mc-optimization-20261025/baseline/scripts/mc-knowledge.py','baseline_cli_sha256':hashlib.sha256(Path('/tmp/hardrush-mc-optimization-20261025/baseline/scripts/mc-knowledge.py').read_bytes()).hexdigest(),'project':'/Users/leifwebber/projects/minecraft/hardrush','cache_dir':str(r/'cache'),'scoring':'每题100=90事实+10证据；量表不因未问细节硬扣分','source_scope':'Java26.3原版服务端，非HardRush应用Mixin后的行为','manifest':'SHA256SUMS覆盖标准答案、量表、精简证据、原始查询输出和独立cache，排除manifest本身'}
(r/'provenance.json').write_text(json.dumps(provenance,ensure_ascii=False,indent=2)+'\n')
(r/'README.md').write_text('''# 新题独立冻结基准

T2-answer.md / T2-rubric.md：铁傀儡随机伤害与玩家难度处理。
T3-answer.md / T3-rubric.md：普通刷怪笼暂停、重设条件与试炼状态机区别。
key-evidence.md：关键原文和出处；raw/＋queries.jsonl：全部原始命令及JSON；provenance.json：版本/JAR/工具身份。
SHA256SUMS：冻结文件摘要（包括独立缓存）。

未读实验答案，未改仓库，未启动游戏或真实浏览器。题目可用，不更改题意。伤害单位均为伤害点，最终伤害指命中伤害值，不是当前剩余生命能够扣掉的最大量。刷怪笼的800是随机排除上界；试炼笼不能套普通笼离开就暂停的规则。
''')
