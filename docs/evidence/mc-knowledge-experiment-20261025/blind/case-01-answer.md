**结论：Java 版 26.3 中，骷髅转化为流浪者分为约 7 秒准备、约 15 秒抖动两个阶段；野生狼和可正常行动的驯服狼，都会主动攻击流浪者。**

本次直接反编译了本机缓存中的 **26.3 正式版原版服务端 JAR**，并用字节码复核关键逻辑。版本标记及文件校验记录见[证据清单](<workspace>/evidence-26.3/provenance.json)；该版本也有[官方发布页](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)。

**1．骷髅在细雪中的完整转化过程**

| 阶段 | 行为 | 配置时长 |
|---|---|---|
| 准备 | 持续处于细雪内，累计准备计数；尚未出现转化抖动 | 140 tick，约 7 秒 |
| 转化 | 开始抖动，同时启动转化倒计时；此时仍是骷髅 | 300 tick，约 15 秒 |
| 完成 | 原骷髅被替换为流浪者，保留手持物与装备；新流浪者恢复满血 | 合计约 22 秒 |

140、300、转化目标和抖动条件直接写在 [Skeleton.java](<workspace>/evidence-26.3/src/net/minecraft/world/entity/monster/skeleton/Skeleton.java:18) 中。[Wiki 的转化说明](https://minecraft.wiki/w/Stray#Converting)也给出“7 秒后抖动，再过 15 秒转化”，并说明满血和保留装备。

有几个影响实际结果的细节：

- **两个阶段都必须持续留在细雪内。**包括已经开始抖动后，只要代码检测到离开细雪，就会清除准备进度、取消转化状态；重新进入后重新计时，而不是继续剩余时间。见 [ConversionTracker.tick()](<workspace>/evidence-26.3/src/net/minecraft/world/entity/ConversionTracker.java:53)。
- **判定是 `isInPowderSnow`，没有要求头部或眼睛浸没。**细雪效果先设置该标记，再单独判断是否能冻结，所以皮革护甲的防冻效果不会直接阻止转化；皮靴使骷髅站在雪面、没有进入细雪，则另当别论。见 [InsideBlockEffectType.java](<workspace>/evidence-26.3/src/net/minecraft/world/entity/InsideBlockEffectType.java:6)。
- **普通骷髅不会因这一过程受到冻结伤害**，其 `canFreeze()` 明确返回 `false`。计时仅在服务端、实体存活且 AI 未关闭时推进。出处分别为上述 `Skeleton.java` 和 `ConversionTracker.tick()`。

**逐 tick 的严格边界：约 22 秒不等于代码保证恰好 440 tick。**26.3 中，离雪会把准备计数设为 `−1`；通常从雪外进入，需要 **141 次连续雪内检查**才达到 140。随后倒计时从 300 先递减，直到 **小于 0**才完成，因此还需 **301 次检查**。这条通常路径共 **442 次有效检查，按 20 TPS 折算约 22.10 秒**；若准备计数初态为构造时的 0，则少一次检查。依据仍是上述 `ConversionTracker.tick()`，亦可查阅[独立字节码反汇编](<workspace>/evidence-26.3/bytecode/ConversionTracker.javap.txt)。画面上的入雪瞬间还涉及 tick 对齐，不能据此保证秒表恰好测出 22.10 秒。

**2．野生狼和驯服狼是否主动攻击流浪者**

| 狼的状态 | 结论 |
|---|---|
| 自然生成、未驯服 | **会主动攻击**，不需要先被流浪者攻击 |
| 已驯服、站立且可正常行动 | **同样会主动攻击**，不需要主人先攻击流浪者或被其攻击 |
| 已驯服、被命令坐下 | 常规地面坐下状态下，**通常不会主动起身追击**；坐下行为优先于近战行为。狼受伤会解除坐下命令 |

关键证据是 [Wolf.registerGoals()](<workspace>/evidence-26.3/src/net/minecraft/world/entity/animal/wolf/Wolf.java:131)：

```java
new NearestAttackableTargetGoal(this, AbstractSkeleton.class, false)
```

这条规则**没有“必须未驯服”的限制**，而 [Stray.java](<workspace>/evidence-26.3/src/net/minecraft/world/entity/monster/skeleton/Stray.java:21) 明确是 `Stray extends AbstractSkeleton`。相邻的动物猎物规则才使用额外检查未驯服状态的 `NonTameRandomTargetGoal`，不能把该限制套到流浪者身上。

“会主动攻击”表示具有主动选敌机制，实际攻击仍受距离、可见性、寻路及其他更高优先级行为影响。坐下行为见 [SitWhenOrderedToGoal.java](<workspace>/evidence-26.3/src/net/minecraft/world/entity/ai/goal/SitWhenOrderedToGoal.java:11)，受伤解除坐下见 [Wolf.hurtServer()](<workspace>/evidence-26.3/src/net/minecraft/world/entity/animal/wolf/Wolf.java:391)。

以上结论限定于 **Java 版 26.3 原版**。机制与 tick 边界来自静态代码核验；未启动游戏客户端或服务器，未进行实机秒表测量。