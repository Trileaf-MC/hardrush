# 本次原版机制调查证据

目标：Minecraft Java Edition 26.3 正式版。仅静态读取原版服务端 JAR，并使用 Vineflower 1.12.0 与 javap 核查；没有运行游戏客户端或服务器。

官方版本入口：https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3

提取后服务端 JAR SHA-256：`a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6`。`doctor.json` 保存本地工具与完整性检查；`version.json` 来自该 JAR。注意：doctor 的完整性结果是与本地服务端 bundle 中记录的 SHA-256 一致，并非本次重新下载官方整个服务端文件后的比对。

`provenance.json` 保存每次调用的参数与来源。各 `.json` 是保留 MC_KNOWLEDGE_FORMAT 预设的 CLI 原始输出；同名 `.java` / `.txt` 是其中的完整正文。带方法名的 Java 文件属于反编译方法视图，行号是此视图行号，不是 Mojang 原始源码行号。

## 关键定位

- `Skeleton.java`：140 tick 准备参数、300 tick 转化参数、细雪判定、颤抖状态、canFreeze。
- `ConversionTracker.java`：tick 中的准备与倒计时、离开细雪重置、存读档、转化调用。
- `Skeleton.constructor.bytecode.txt` 与 `ConversionTracker.tick.bytecode.txt`：验证上述常数、比较与重置分支。
- `InsideBlockEffectType.java` 与 `PowderSnowBlock.entityInside.java`：细雪接触设置状态的路径。
- `ConversionType.java`：转化保留装备、名称与状态效果等。
- `Wolf.registerGoals.java` / `.bytecode.txt`：骷髅类目标使用普通 NearestAttackableTargetGoal，优先级 7。
- `Stray.java`：流浪者继承 AbstractSkeleton。
- `NonTameRandomTargetGoal.java`：作为区分未驯服限定 Goal 的直接代码证据。
- `SitWhenOrderedToGoal.java`、`Wolf.hurtServer.java`、`OwnerHurtByTargetGoal.java`、`OwnerHurtTargetGoal.java`：坐下与受伤、协助主人攻击的条件。

## 复查示例

在仓库根目录执行（沿用 MC_KNOWLEDGE_FORMAT，不覆盖格式）：

```sh
python3 scripts/mc-knowledge.py --version 26.3 doctor
python3 scripts/mc-knowledge.py --version 26.3 --max-lines 150 source ConversionTracker
python3 scripts/mc-knowledge.py --version 26.3 source Skeleton --member '<init>'
python3 scripts/mc-knowledge.py --version 26.3 source ConversionTracker --member tick --bytecode
python3 scripts/mc-knowledge.py --version 26.3 source Wolf --member registerGoals
python3 scripts/mc-knowledge.py --version 26.3 source Stray
```

计时边界：此前雪外有效检测会把 afflictionTime 设成 -1；再次进入后要 141 次有效检测才能达到 140。转化时间从 300 开始，每次减一且在小于 0 时转化，因此第二阶段是 301 次更新。通常合计 442 次有效检测；若初始 afflictionTime 仍为构造器的 0，则是 441 次。接触细雪到第一次计时检测的差异须结合实体 tick 顺序；这里不把代码计数描述为运行时实测。秒数均以 20 TPS 换算。
