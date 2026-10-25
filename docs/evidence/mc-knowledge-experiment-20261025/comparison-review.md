# Minecraft 机制研究路线：9 次运行的独立比较审查

本轮对 A（指定项目 skill＋CLI）、B（提示 Wiki／本机 Gradle＋Vineflower）、C（不提示路线）各执行三次同一道问题。所有运行的 native 配置均记录为 `gpt-6.1-sol / high`，全部完成，无压缩记录。本审查核对完整 9 次 `metrics.json`、manifest、盲化映射、两份评分和裁决、288 个噪声窗口标注，以及可观测工具调用和结果；没有读取初始私有指令，没有修改 skill 或 CLI。更新后的 `summary.json` 已与每次 native 指标交叉核对，不能把早先仅六次的聚合用于本结论。

在这个具体问题和现有机器环境里，A 对 C 有正向收益：完整度中位数高 5 分，同时峰值输入、中位未缓存输入和时间更低。A 对 B 的收益主要表现为较小的输入及工具输出体量，时间中位数几乎相同、完整度反而低 2 分。不能据此声称 skill／CLI 全面胜过已经知道源码路线的研究者，也不能把本轮完整度分数解释为事实正确率。

## 数量结果及指标口径

下表均为三次运行的**中位数〔最小—最大〕**。除字符数和评分外，以下 token 数来自运行时 native usage／单次调用记录，不使用 o200k 估算替代。

| 指标 | A：skill＋CLI | B：提示现成路线 | C：无路线提示 |
|---|---:|---:|---:|
| 固定总分／100 | 85〔83—85〕 | 87〔85—87〕 | 80〔79—84〕 |
| 事实与完整度／90 | 75〔73—75〕 | 77〔75—77〕 | 70〔69—74〕 |
| 证据／10 | 10〔10—10〕 | 10〔10—10〕 | 10〔10—10〕 |
| 端到端时间，秒 | 254.326〔250.361—255.265〕 | 257.679〔241.267—349.639〕 | 345.945〔237.544—377.357〕 |
| 单次调用最大输入 token | 80,229〔68,566—82,105〕 | 85,809〔81,334—90,159〕 | 96,553〔95,169—105,587〕 |
| 首次调用输入 token | 13,375〔13,375—13,418〕 | 13,300〔13,300—13,343〕 | 13,257〔13,257—13,300〕 |
| 峰值输入减首次输入 | 66,854〔55,148—68,730〕 | 72,509〔68,034—76,816〕 | 83,296〔81,912—92,287〕 |
| 累计输入 token | 657,297〔560,742—728,880〕 | 745,429〔664,978—1,253,556〕 | 873,091〔711,754—979,384〕 |
| 累计缓存输入 token | 591,104〔495,744—658,816〕 | 651,264〔596,480—1,180,032〕 | 751,616〔629,504—889,216〕 |
| 累计未缓存输入 token | 66,193〔64,998—70,064〕 | 73,524〔68,498—94,165〕 | 90,168〔82,250—121,475〕 |
| 累计输出 token | 7,215〔7,135—7,925〕 | 7,965〔7,815—10,992〕 | 9,517〔7,208—11,013〕 |
| 其中 reasoning output token | 1,846〔1,624—1,855〕 | 1,702〔1,106—2,066〕 | 2,337〔1,517—3,372〕 |
| 模型调用次数 | 13〔12—15〕 | 14〔13—20〕 | 14〔13—17〕 |
| 可观测工具返回字符 | 210,793〔172,584—215,902〕 | 242,581〔218,922—244,688〕 | 277,800〔276,468—312,237〕 |
| shell 执行条目数 | 33〔32—42〕 | 21〔20—21〕 | 15〔11—25〕 |
| shell 条目非零退出数 | 1〔1—2〕 | 1〔0—2〕 | 1〔1—2〕 |
| 外层工具输出截断数 | 1〔0—1〕 | 1〔1—2〕 | 3〔1—3〕 |

累计输入是多个模型调用反复传入历史后的总量，绝不表示一次占用了 56—125 万上下文。峰值列只表示观测到的最大单次 native input，包含系统、工具定义和历史；不是模型窗口上限。峰值减首次输入是上下文占用增长的粗代理，含工具结果、助手输出与调用包装，不是新增工具上下文的精确计数。未缓存输入是 native 缓存分类差值，也不是语义上的“新知识／新增上下文”。本实验没有独立 native 新增上下文计量，不应从这三者互相替代推算。

reasoning output 是 output 的细分，不能再次相加。shell 条目数与一次 exec 内批处理的工具返回数也不等同。全九次累计输入 7,175,111、其中缓存 6,443,776、输出 76,785（其中 reasoning 17,425）；没有价格和计费模式证据，不能换算为实际人民币／美元成本。

A 相对 B 的中位峰值输入低 6.5%、未缓存输入低 10.0%、工具返回字符低 13.1%，耗时只低 1.3%。A 相对 C 对应下降 16.9%、26.6%、24.1%，耗时低 26.5%。这些是各组三个中位数的描述性比值，不是置信区间或经过显著性检验的效应。逐波看，A 对 B 的峰值输入及未缓存输入三次均更低；时间两次更快、一次更慢。C 的第一次 237.544 秒比当波 A 250.361 秒更快，因此也不能说 A 每次都更快。

| 运行／组／盲号 | 裁决总分 | 秒 | 峰值 native input | 累计未缓存 input |
|---|---:|---:|---:|---:|
| run-01／A／case-04 | 85 | 250.361 | 82,105 | 70,064 |
| run-02／C／case-02 | 80 | 237.544 | 96,553 | 82,250 |
| run-03／B／case-07 | 87 | 257.679 | 85,809 | 94,165 |
| run-04／B／case-03 | 87 | 349.639 | 90,159 | 73,524 |
| run-05／C／case-05 | 79 | 377.357 | 105,587 | 121,475 |
| run-06／A／case-06 | 83 | 254.326 | 68,566 | 64,998 |
| run-07／A／case-08 | 85 | 255.265 | 80,229 | 66,193 |
| run-08／B／case-01 | 85 | 241.267 | 81,334 | 68,498 |
| run-09／C／case-09 | 84 | 345.945 | 95,169 | 90,168 |

评分采用预先冻结的 100 分量表，不取评审均值替代裁决；case-02 与 case-06 的 S6 均按 6 分，其余项目两位评审一致。原始评分保留，裁决在聚合层应用。

## 路线与失败成本：可直接从日志看出的区别

A 三次都先读取 skill，执行 help／doctor，再按类或成员读取已有官方服务端 JAR。run-01 的 doctor 明确返回项目已存在的 JDK 25、javap、Vineflower 1.12.0、服务端包 SHA-256 及隔离的生成缓存；它没有先尝试失效的系统 Java，也没有下载 JDK 或编写反汇编器。见 [run-01 日志](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/transcript.md:102)，可观测事件 `item_4`。

B 三次也全部成功走向本机 Gradle＋Vineflower，说明“提供源码路线”本身已有很大价值。区别在于自行发现 Java 和制品：run-03 的 Python 包装命令内出现 “Unable to locate a Java Runtime”，随后列举系统安装目录，改用 `/opt/homebrew/opt/openjdk/bin/java`；run-08 重复 `java -version / java_home` 失败、搜索 Homebrew 与应用目录，再恢复反编译。见 [run-03](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-03/transcript.md:43)、[run-08](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-08/transcript.md:65)。run-03 的外层 shell 最终退出 0，故 native `command_failures=0` 不等于内部没有失败。

B 的较慢样本 run-04 不是只在找 Java。它先使用 merged-deobf 制品，后来下载官方客户端，将所选 41 个 class 与本地制品比较，40 个相同，唯一不同的是客户端 AbstractSkeletonRenderer；随后继续验证颤抖渲染，并因目标输出目录不存在造成 `Failed to save class`，建立目录后重试。这是来源／客户端表现深挖的额外成本，不能全归为无用环境探路；关键机制类比较已经一致。见 [41 类比较](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/transcript.md:801)、[差异与重试](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-04/transcript.md:810)。这一路线得到87分，与run-03相同，耗时却增加约92秒，未在固定必答量表上获得额外收益。

C 三次全部自行下载官方包、遭遇本机默认 Java／javap 路径不可用，随后安装 Python `jawa`、编写字节码读取器。run-02 还因 switch 操作数是字典而触发 `AttributeError: 'dict' object has no attribute 'op_type'`，后续修复；run-09 一次 Python 循环对11个类调用失败的 javap，外层仍退出0，又尝试下载 JDK 被403拒绝，最后切换到 jawa。见 [run-02 的安装与错误](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-02/transcript.md:432)、[run-09 的循环失败](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-09/transcript.md:326)、[JDK 请求403](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-09/transcript.md:342)。这解释了为何少量 shell 条目不代表低成本：一个条目可能打印许多类、执行许多失败子进程。

所以，**本轮最清楚的 CLI 优势是把版本／JAR／JDK／反编译器定位与输出管理收进可靠入口，避免反复搭建工具链**。这也属于设计的实际收益，不是应从结果中扣除的“作弊”。但没有“B＋已告知正确 JDK”或“CLI＋去掉 skill”等消融，不能证明成本差异主要由环境探路贡献，更不能给出环境与源码定位各占多少的百分比。A 的有界成员读取与来源记录也是同时变化的因素。

## 收益确实被读取和输出方式稀释

A 没有充分遵守 skill 中的最小相关片段策略。run-07 已读 `Wolf.registerGoals`，仍请求 `source Wolf --max-lines 1000 --max-chars 65000`，得到 Wolf 全类正文29,764字符、JSON31,567字符，随后又读 registerGoals 字节码15,729字符。全类中的项圈、外观、声音、繁殖等并不回答本题。其整批结果还发生外层截断。见 [run-07 全类请求](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-07/transcript.md:123)，事件 `item_20`；噪声盲标 `case-08-13/17/18/19` 分别落在外观同步、通用组件、声音及繁殖，均标为100% I。整类读取不是完全无价值，确实能发现受伤解除坐下；问题是为该具体方法支付了大量旁支阅读成本。

来源元数据也相当重。对三个 A 运行中**可解析的直接 CLI JSON 返回**，按 CLI 自报 `output_chars-body_chars` 计算，正文之外的包装、出处、范围信息及 JSON 转义分别约36,688／27,830／29,757字符，占对应JSON总字符32.3%／31.9%／24.3%。这不是纯粹的“无用元数据”精确量，也不等于全部可见上下文比例；有外层截断时，事件原始返回还可能多于模型实际收到的部分。它仍揭示了重复包装的量级：A 三次的可见工具返回分别出现34／25／26次 `jar_sha256`。例如 run-06 的 `TamableAnimal.canAttack` 正文226字符，而JSON达1,401字符（`item_25`）。第一次保留完整来源有价值，每个短成员重复长哈希和路径会稀释该价值。

A 也没有摆脱网页噪声。三次第二个工具返回均约35,000字符，包含 CLI help 与大段搜索结果；官方26.3发布页用于确认版本，却连带返回大量树木、地图、菜单和商店导航。A 仍执行了与 B/C 相同类型的公开检索。skill 的 Wiki 分节、固定修订等能力本轮没有成为主要可观测路线，不能从结果判断其有效性。日志中也没有发现 B/C 调用项目 CLI 或读取该 skill；可观测日志未出现记忆文件路径，但这不能证明已消除基础模型先验。

CLI 仍有可避免的摩擦：run-01 查询不存在的 `TamableAnimal.reassessTameGoals`；run-06／07 均在 `Mob.convertTo` 未给 descriptor 时得到重载歧义，再补 descriptor；run-06 还误用 `--java`，被 argparse 当作 `--java-home` 前缀而报缺参数。见 [run-01](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-01/transcript.md:333)、[run-06](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/transcript.md:104)。这些错误小于重新建立字节码工具链，但不能声称 CLI 已消除失败和冗余。

## 噪声盲标：内容更集中，但仍远非精简

每份工具返回抽取32个约128-token窗口，共288个窗口；审计者在盲态下按10%步进估计 E（直接证据）、O（来源／导航／包装）、I（无关正文）、F（失败或丢失提示）。下列为三次比例的中位数〔范围〕，各列中位数独立计算，因此不要求相加等于100%。

| sampled比例 | A | B | C |
|---|---:|---:|---:|
| E | 15.0%〔9.4—16.9〕 | 15.6%〔14.7—20.9〕 | 12.2%〔12.2—19.7〕 |
| O | 46.3%〔42.2—49.1〕 | 40.0%〔34.7—45.3〕 | 27.8%〔27.5—28.4〕 |
| I | 36.3%〔34.1—48.4〕 | 43.8%〔39.1—45.3〕 | 58.1%〔48.4—60.0〕 |
| F | 0%〔0—2.5〕 | 0%〔0—0.6〕 | 2.2%〔0—3.4〕 |

A 比 C 的无关正文占比更低，但 A 第三次48.4%与 C 第二次48.4%相同，也高于全部 B 样本的 I，因此“用了CLI便保证输出精简”不成立。A 较高的 O 与上述 JSON 出处及网页导航相符；O 不能全部当作无用，E 低也不能直接推断答案不准确。窗口是用途标注，不是对每段内容真假的复核。

仅作敏感性参考：用本地 o200k 编码估计全部可见工具返回，再乘抽样 I 比例，A／B／C 的无关返回量中位数约21,427／28,055／42,915，范围约18,168—29,871／26,430—28,995／40,355—46,739。**这不是模型真实 token 使用或精确无关上下文量**；32窗口抽样与10%人工粒度均有误差，不应将A相对C约50%的估算下降表述为精确模型token节省。metrics中 `tool_output_unique_tokens_o200k` 与总量相同，仅能说明按该去重办法未合并输出，不能否认语义重复、重复来源和重复源码。

## 完整度取舍与可以支持的结论

九次均正确覆盖本轮两个核心结论：26.3离开细雪会取消已开始转化，驯服狼保留骷髅类自主选敌。九次也都给出有效的版本与源码／字节码出处，所以证据分全部10分。这说明 C 也能独立找到正确证据；本轮没有体现出 skill 对这两个核心事实的独有准确性优势。

固定量表的差异主要来自交付遗漏。A/B 全部说明 NoAI 对计时的限制，C 全部没有明确说明，形成3分稳定差异；B 对冻结与独立计时的表述更完整，C 的一个样本未区分普通动物猎物。与此同时，所有样本都漏了“主人被攻击”和“主人攻击目标”的两个独立协助触发（W3的6分），也漏了“命令坐下使两个主人协助目标不能启动”（W4的5分），所有样本都没有明确转化本身不禁用普通AI（S5的2分）。这些共同缺失不能由长篇精确441／442调用推导、装备保留或满血说明补分。

尤其 A 三次都读取了 OwnerHurt*Goal 内容：run-01 的 `item_27/28`、run-06 的 `item_21/23`、run-07 的 `item_35/36`；最后仍遗漏对应必答解释。见 [run-06 护主来源读取](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-06/transcript.md:129)、[run-07](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/runs/run-07/transcript.md:218)。所以增加检索量不是这里的主要补救；需要让已收集证据映射回提问的交付要求。狼自身受伤解除坐下是准确例外，不能与主人受伤混同；审查和裁决已保持这一边界。

本结论只适用于这道机制题、这些源码与缓存、同一模型配置和机器环境。三波各组并行，生成源码／Wiki缓存按运行隔离，已有原始Gradle缓存共享；manifest明确 OS页缓存和网络缓存未重置。模型服务延迟、并行资源竞争和不可控先验也未消除。manifest的随机化seed不等于已控制模型采样随机数。重复同一问题三次不能代替跨题目样本，不能证明总体显著性；也不能外推到纯Wiki问题、数据JSON问题、无本机JAR的环境、其他版本或真实客户端验证。

## 建议

保留并继续默认使用 skill＋CLI：本轮相对无路线提示获得了可复核的成本／完整度正向结果，且避免了反复寻找运行时、下载包、建立解析器。对已经熟悉Gradle源码路线的人，合理预期应是减少环境与出处管理、略降输入量，不能承诺明显更快或更准确。

下一步优先优化实际暴露的浪费：一次完整身份校验后，用短来源引用复用哈希和制品身份；短成员查询返回紧凑包装，批量查询共用出处；已知关键成员时按成员读取，发现具体问题再取整类；核对出处标题和相关段落后停止读整页。CLI的重载歧义可给可复制的descriptor后续调用，拒绝参数缩写可避免`--java`落到`--java-home`。这些是建议，本审查没有实现或修改工具。

交付阶段增加简短的“问题要求—证据—最终结论”检查，尤其覆盖AI开关、冻结与转化分离、主人两种触发、命令坐下限制；把441／442、装备／满血、客户端渲染等可选深挖放在必答闭环之后，避免用旁支精度替代完整回答。后续验证可加入多道不同机制题，并增加“只给正确JDK／JAR路径”的中间条件与紧凑CLI输出条件，才能分别估计环境探路和检索接口的贡献。

复核入口：[manifest](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/manifest.json)、[native metrics](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/metrics.json)、[完整聚合与裁决](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/summary.json)、[噪声口径](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/noise-audit/notes.md)、[固定量表](/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025/reference/rubric.json)。
