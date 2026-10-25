# 复测过程审查（final：27/27）

27次正式primary attempt全部completed，runner正常退出、没有重跑；T1/T2/T3各9次，J/O/S各9次。本版保留前15次中期已保存的逐项观察，仅新读取run-16—run-27的调用/工具返回，没有重复读取前15次全日志。随后仅用最终metrics.json的数值与控制字段交叉核对。此审查不参与答案评分：未读取最终answer.md、量表或评分，未触发CLI、模型、游戏或浏览器，未修改脚本、运行输入、status或原日志。

## 控制变量与格式采用

27/27实际cat各自SKILL.md，且工具返回全文SHA-256与起始清单skill哈希完全一致；不只是prompt提到可用skill。27/27在各次结束后核对的初始输入路径内容均匹配initial-files.json；最终analyze也再次报告27次initial_input_integrity=verified。原生configuration的cwd全部对应自己的workspace。没有观察到CLI、输出模块、skill或配置的结束态改写/缺失；这仍不能排除中途改写后恢复。

|组别|可确认业务wire返回|格式unknown|单列预算错误JSON|显式异格式/env覆盖|
|---|---:|---:|---:|---:|
|J|JSON 210|1|0|0 / 0|
|O|compact 222|0|0|0 / 0|
|S|compact 212＋JSON诊断1|16|1|0 / 0|

分别识别J/O/S的211/222/229次业务调用。可确认、可纳入采用率的644次返回全部遵循各组格式预设；17次unknown不以env代替确认。另有J组7条未能静态识别为具体CLI调用的候选，不能混入已辨认调用分母，也不能把它们都算成实际执行；其中能明确看到3条含嵌套CLI subprocess的脚本。doctor/help不计入业务格式采用分母。

唯一JSON例外是[run-07 / item_16](runs/run-07/attempt-01/events.jsonl:36)：compact预设下请求构造器，CLI因minimum_required_chars=9045超过budget=6000，返回156字符的OUTPUT_BUDGET_TOO_SMALL单行JSON诊断。没有format/env覆盖。真实wire为JSON，但这是CLI契约内错误fallback，单列且不归为参与者格式违规、不进入采用率分母；错误调用、后续成本和整个样本全部保留。精确判定条件见[analysis-clarifications-extra.md](analysis-clarifications-extra.md)。最终metrics交叉核对三组CLI事件级protocol_deviations均为0。

compact也不等于全过程固定6000字符预算。O有80次显式--max-output-chars，其中79次>6000、1次为5000，请求范围5000—38000；S有71次，均>6000，请求范围8000—25000。J无此参数。显式请求上限不是实际输出长度，但这些事实足以否定“所有compact输出都一直受默认6000上限约束”的解释。

## 禁止材料读取核查

在已审查的实际操作及返回中，没有确认读取lab/reference、旧实验/参考目录、其他run的workspace、既有调查报告或个人记忆。可见本地读取路线主要是自己的skill、CLI/config、自己新生成的缓存/证据，以及明确允许的Gradle原版JAR/Java工具；web路线为公开Minecraft资料。实际工具返回没有命中所列已知禁区路径前缀。

唯一路径候选仍是[run-09 call_255c88ffdcc14ba987a07ec072f97631](runs/run-09/attempt-01/observable.jsonl:41)：实际apply_patch向自己的workspace/.local/vanilla-26.3-evidence/README.md新增本次证据说明，是写自己的新报告，非读取其他实验或旧报告。该报告自述“未读取历史”不被当成独立证明。剩余12次没有新增候选或禁区路径返回命中。

结论仅为“在所审查的可见工具轨迹中未确认污染”，不能证明绝无污染。日志不是系统调用审计，Python内部子进程没有逐次独立I/O事件，变量/别名路径、未打印的读操作和模型既有知识也无法被字符串扫描排除。cwd内的rg/git状态探查、.gradle原版缓存读取和本次自己的证据写入不能仅因路径看起来广泛就被误判为污染。

## 可审计的直接过程计数

计数只含能从真实command_execution静态辨认的直接Python CLI调用，是下界。无member初始类视图定义为source未给--member且未给--start-line；本批未发现仅给--start-char而无start-line的歧义情况。它是请求次数，允许重复请求同一类，不代表返回了整个类，也不代表该请求没有价值。

|组别（各9次）|help|doctor|无member初始类视图|无member全部请求（含续页）|有member请求|官方发布页直接打开|含仅版本身份查询的web调用|
|---|---:|---:|---:|---:|---:|---:|---:|
|J|14|9|58|68|95|7|2|
|O|17|11|51|62|117|7|6|
|S|18|13|41|46|131|5|7|

直接help合计49次、doctor合计33次，27个样本均有至少一次help和doctor。S没有在此小样本中消除或减少这两类入口调用。无member初始请求合计150次；按题目拆开后差异更清楚：

|题目（每组各3次）|J：无member初始 / 有member|O：无member初始 / 有member|S：无member初始 / 有member|
|---|---:|---:|---:|
|T1|35 / 42|35 / 43|23 / 56|
|T2|0 / 30|0 / 35|0 / 32|
|T3|23 / 23|16 / 39|18 / 43|

不能把S的合计41次无member初始请求直接称为普遍改进。T1中S为23次，低于J/O各35次；T2三组均为0；T3中S为18次，反而高于O的16次（J为23）。S的有member请求总数131次也高于O的117次和J的95次，可能是更细粒度阅读、额外深挖或重复核查，单凭数量不能判断更高效或更准确。

官方26.3发布页实际直接打开19次（J7/O7/S5），标题/URL都在实际返回中确认。另有15次web调用至少包含一条仅版本身份的官方域查询（J2/O6/S7）；严格按查询字面筛选，仅允许Minecraft/Java/Edition/26.3/version/release/official等身份词，排除conversion、spawner等机制词。复合web调用可能同时含其他机制查询，不能称整个调用只为确认版本。

版本重复核实的例子是run-04：[item_4 doctor](runs/run-04/attempt-01/events.jsonl:10)已给出本地来源信息，随后[web open调用](runs/run-04/attempt-01/observable.jsonl:12)仍打开指定26.3发布页。run-06/run-14也在配置/doctor路线之外打开该页；T3的J/O各打开3次、S打开1次，但S另两轮仍有官方域版本搜索。发布页有时还被find skeleton/conversion/wolf等机制词，不能把全部页面访问都归为单纯版本确认，更不应换算成主观无关比例。若干返回头报告源文档3485行，开头可见菜单导航；不能把这当成3485行全部进入上下文，也不能把混合functions.exec外层长度当成页面净成本。

## 实际过程例子与反例

支持“成员视图＋紧凑正文”机制的例子：[S / run-01 item_11](runs/run-01/attempt-01/events.jsonl:29)直接请求Wolf.registerGoals，compact返回2664字符、complete且无next；[S / run-15 item_7](runs/run-15/attempt-01/events.jsonl:19)和[item_8](runs/run-15/attempt-01/events.jsonl:20)分别以1289/1966字符返回两个目标成员完整视图。T1的S确实观察到较少无member类视图和较多成员请求。这是可见行为，不是新skill因果收益或答案正确性的证明。

并非S始终先成员。T3三个S样本的第一次source都是无member类请求：[run-21 item_13 TrialSpawnerConfig](runs/run-21/attempt-01/events.jsonl:28)、[run-24 item_13 TrialSpawnerConfig](runs/run-24/attempt-01/events.jsonl:33)、[run-27 item_13 TrialSpawnerState](runs/run-27/attempt-01/events.jsonl:30)。反而O的[run-22 item_12](runs/run-22/attempt-01/events.jsonl:28)第一次source已指定BaseSpawner.serverTick成员。类配置/状态机阅读可能有正当需要；此处仅用于反驳“新skill自动让每个首查都变成成员查”的过度归因，不判这些选择对错。

首响应短也不等于调查净输出少。[run-01 item_14](runs/run-01/attempt-01/events.jsonl:34)、[item_15](runs/run-01/attempt-01/events.jsonl:37)显式提高预算并读取类视图；[item_23](runs/run-01/attempt-01/events.jsonl:44)又读取证据记录并doctor --provenance full。[run-15 item_15](runs/run-15/attempt-01/events.jsonl:32)循环读取打印4份证据记录，stdout为12961字符；T3中[run-21 item_31](runs/run-21/attempt-01/events.jsonl:60)的证据读取命令stdout为15803字符，[O / run-20 item_35](runs/run-20/attempt-01/events.jsonl:72)直接cat记录返回13835字符。

全量观察到O有16条、S有14条读取CLI evidence目录记录的命令；J为0，但J仍读取artifacts源码、原版JAR及自己导出的证据文本，且有3条嵌套CLI脚本，不能解读成J没有额外文件读取。evidence命令有的打印完整记录，有的只打印元数据，因此也不能把命令数直接当作文件数、token成本或噪声评分。

错误恢复反例同样保留：[run-07 item_16预算失败](runs/run-07/attempt-01/events.jsonl:36)后，[item_21](runs/run-07/attempt-01/events.jsonl:49)转为无member类视图并给14000字符预算，实际返回4194字符且complete。它展示一次额外恢复路线，不说明所有大成员必须读整类；预算fallback也没有被删除、补跑或从整个试验中排除。

## 证据界限

本报告不评最终答案、事实正确性或引用质量，不用最终交付内容推断工具调用动机，不量化主观“无关比例”。三题各每组仅3次，行为差异可能同时受题目、随机路线、源码结构、额外核查和输出预算选择影响，不能仅凭这些过程计数推出LLM因果收益。

直接调用数量不是累计模型token。这里的stdout字符不含完整native外层包装；既定tool_output_tokens_o200k又包含非string存储JSON的转义，补充decoded text-part也仍可能含包装，不能混称净正文或计费。原始调用/返回可定位，难拆分的混合返回保留unknown和局限。

27次逐项观察、hash、命令事件ID/行号、格式判定、路径候选处置及按题目分组计数见[process-observations.json](process-observations.json)。最终metrics数值交叉核验为27 completed、27输入完整性verified、0参与者格式协议偏离；这不替代独立答案盲评。
