# Minecraft Java Edition 26.3 原版机制核查材料

本目录为本次调查新生成的材料。仅静态读取原版缓存并运行 Vineflower / javap，未运行游戏客户端或服务端。

## 版本与复核

- 官方发行说明：https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3
- 发行说明中的服务端下载：https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar
- 本机外层服务端 JAR SHA-1：`33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`，与官方下载地址中的摘要一致。
- 输入：`/Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar`
- 输入 SHA-1：`cc3964451a3b32a0488110d5e3e6ad8d75fe6a66`
- 版本文件：`resources/version.json`，`id=26.3`、`stable=true`。
- 反编译器：Vineflower 1.12.0；输出位于 `decompiled/`；原始选取类位于 `classes/` 和 `extra-classes/`。
- `manifest.json` 保存关键 class 的 SHA-256；`bytecode/` 保存 javap 输出。

可用 JDK 的 javap 独立复核，例如：

```sh
/opt/homebrew/opt/openjdk/bin/javap \
  -classpath /Users/leifwebber/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar \
  -c -p net.minecraft.world.entity.ConversionTracker
```

将最后的类名替换为 `net.minecraft.world.entity.monster.skeleton.Skeleton` 或 `net.minecraft.world.entity.animal.wolf.Wolf`，即可复核参数和目标注册。

## 关键代码定位

1. `Skeleton`：140 tick 预备阈值、300 tick 转化倒计时；条件仅为 `isInPowderSnow`；`isShaking()` 对应转化状态；`canFreeze()` 为 false。
2. `ConversionTracker.tick()`：只在服务端、存活且未禁用 AI 时推进；预备计数先加一再比较 `>=140`；开始转化时倒计时设为 300，之后先减一，在 `<0` 时转换；不满足细雪条件时设预备计数为 -1，并关闭转化状态。
3. 因此第二阶段严格需要 301 次后续递减。从预备计数 0 / -1 开始，连续细雪条件下需要 140 / 141 次预备调用，加上 301 次递减调用，共 441 / 442 次满足条件的 tracker 调用。这是静态计数，不是从碰撞发生瞬间测得的客户端显示时间。
4. `InsideBlockEffectType.FREEZE`：先设置细雪标志，再根据 `canFreeze()` 决定是否增加普通冻结值。骷髅转化计数不依赖普通冻结值或皮革护甲免冻条件。
5. `ConversionTracker.doConversion()` 使用 `ConversionParams.single(mob, true, true)`；`ConversionType.SINGLE` 转移装备和掉落概率；新实体由 `Mob.convertTo()` 创建，`LivingEntity` 构造函数初始化为满血，转换代码不复制旧实体的当前生命值。
6. `Wolf.registerGoals()`：对 `AbstractSkeleton.class` 使用普通 `NearestAttackableTargetGoal`，无驯服条件；`Stray extends AbstractSkeleton`。动物猎物使用的是另一个带 `!isTame()` 条件的 `NonTameRandomTargetGoal`。
7. 坐下目标优先级为 2，跳扑为 4、近战为 5；`SitWhenOrderedToGoal` 占用 MOVE/JUMP。因此正常命令坐下时不主动追击。狼自身受到有效攻击时，`Wolf.hurtServer()` 取消坐下命令。

Vineflower 对少量未提供完整依赖的、不作为本调查依据的枚举 switch 方法报告了简化错误，详见 manifest；核心转化逻辑另以 javap 字节码核对。
