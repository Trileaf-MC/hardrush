# T3 冻结标准答案

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
