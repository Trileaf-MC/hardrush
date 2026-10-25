from pathlib import Path
import json, statistics as st

B=Path(__file__).resolve().parent
def read(name):return json.loads((B/name).read_text())
S=read('analysis-summary.json');M=read('metrics.json');D=read('deterministic-output/results.json')
TOPIC={'T1':'骷髅／狼（原题）','T2':'铁傀儡伤害（新题）','T3':'普通／试炼刷怪笼（新题）'}
def f(v,d=0):return f'{v:,.{d}f}'
def pct(v):return f'{v*100:+.1f}%'
def median(topic,arm,key):return S['groups'][topic+'/'+arm][key]['median']
def mean(topic,arm,key):return S['groups'][topic+'/'+arm][key]['mean']

overall=['| 组别（各9次） | 峰值输入中位数 | 工具文本估算中位数¹ | 耗时中位数 | 质量中位数／均值² |', '|---|---:|---:|---:|---:|']
for arm,label in [('J','J：JSON＋旧 skill'),('O','O：compact＋旧 skill'),('S','S：compact＋新 skill')]:
 overall.append(f'| {label} | {f(median("all",arm,"peak_input_tokens"))} | {f(median("all",arm,"tool_output_decoded_text_tokens_o200k"))} | {f(median("all",arm,"elapsed_seconds"),1)} s | {f(median("all",arm,"quality_mean"),1)} / {f(mean("all",arm,"quality_mean"),2)} |')

topic_table=['| 题目 | J 峰值输入 | O 峰值输入 | S 峰值输入 | 输出层 O/J | skill S/O | 组合 S/J |', '|---|---:|---:|---:|---:|---:|---:|']
quality_table=['| 题目 | J 质量中位数／均值 | O 质量中位数／均值 | S 质量中位数／均值 | J / O / S 耗时中位数（秒） |','|---|---:|---:|---:|---:|']
for topic in TOPIC:
 cs=S['contrasts'];topic_table.append('| '+TOPIC[topic]+' | '+' | '.join(f(median(topic,a,'peak_input_tokens')) for a in 'JOS')+' | '+' | '.join(pct(cs[topic+'/'+a+'->'+b]['peak_input_tokens']['relative_change']) for a,b in [('J','O'),('O','S'),('J','S')])+' |')
 quality_table.append('| '+TOPIC[topic]+' | '+' | '.join(f(median(topic,a,'quality_mean'),1)+' / '+f(mean(topic,a,'quality_mean'),2) for a in 'JOS')+' | '+' / '.join(f(median(topic,a,'elapsed_seconds'),1) for a in 'JOS')+' |')

storage=['| 组别 | 既定存储表示估算中位数 | 解码文本段估算中位数 | 累计原生输入中位数 | 累计缓存输入中位数 | 累计输出中位数 | 模型请求数中位数 |','|---|---:|---:|---:|---:|---:|---:|']
for arm in 'JOS':
 storage.append('| '+arm+' | '+' | '.join(f(median('all',arm,k)) for k in ['tool_output_tokens_o200k','tool_output_decoded_text_tokens_o200k','cumulative_input_tokens','cumulative_cached_input_tokens','cumulative_output_tokens','model_calls'])+' |')

det=['| 固定查询 | JSON 输出 token估算 | compact 输出 token估算 | 变化 | 完整正文相同 |','|---|---:|---:|---:|---|']
for row in D:
 j=row['json']['all_stdout_tokens_o200k'];c=row['compact']['all_stdout_tokens_o200k']
 det.append(f'| `{" ".join(row["query"]["argv"])}` | {j} | {c} | {pct(c/j-1)} | 是 |')
jtotal=sum(r['json']['all_stdout_tokens_o200k'] for r in D);ctotal=sum(r['compact']['all_stdout_tokens_o200k'] for r in D)
jchars=sum(r['json']['all_stdout_chars'] for r in D);cchars=sum(r['compact']['all_stdout_chars'] for r in D)
proof_total=sum(r['comparison']['compact_first_response_plus_provenance_tokens_o200k'] for r in D)

detail=['| 运行 | 题目 | 组别 | 重复 | 峰值输入 | 工具文本估算¹ | 秒 | 评分1 / 评分2 | 双评均分 |','|---|---|---|---:|---:|---:|---:|---:|---:|']
for r in S['rows']:
 detail.append(f'| [{r["id"]}](runs/{r["id"]}/attempt-01/portable-answer.md) | {r["topic"]} | {r["arm"]} | {r["repeat"]} | {f(r["peak_input_tokens"])} | {f(r["tool_output_decoded_text_tokens_o200k"])} | {f(r["elapsed_seconds"],1)} | {f(r["grade_1"],1)} / {f(r["grade_2"],1)} | {f(r["quality_mean"],1)} |')

statuses=[read(f'runs/{r["id"]}/attempt-01/status.json') for r in S['rows']]
duration=max(x['end_unix'] for x in statuses)-min(x['start_unix'] for x in statuses)
formats=S['format_event_summary'];eligible=sum(v.get('adoption_eligible_invocations',0) for v in formats.values());confirmed=sum(v.get('adoption_eligible_confirmed_visible_format',0) for v in formats.values());unknown=sum(v.get('unknown_visible_format',0) for v in formats.values());candidates=sum(v.get('unknown_candidates',0) for v in formats.values());fallbacks=sum(v.get('documented_budget_error_fallbacks',0) for v in formats.values())
major=sum(bool(g.get('major_errors')) for case in S['quality_findings'] for g in case['reviews'])
assert major==0
report=f'''# Minecraft knowledge skill／CLI 优化与消融复测

2026-10-25。四项优化已经实现；50项CLI单元测试、12项旧／新JSON真实查询兼容检查通过。使用GPT-6.1 Sol high完成27次正式调查，三个题目、三组、每格三次；全部首次运行均交付答案，没有补样。54份独立匿名评分均已完成。

**这轮收益主要是减少上下文，尚未证明答案完整度提高。** 在同一新CLI上，紧凑输出使九次样本的峰值输入中位数下降4.5%，再换新skill额外下降7.7%，组合下降11.9%。逐题看，新skill相对紧凑输出＋旧skill的中位峰值输入分别下降4.9%、6.6%、15.2%；单改输出在刷怪笼题上没有观察到同样收益。

{chr(10).join(overall)}

¹ o200k_base对解码后的原生工具文本段估算，包含外层工具包装；不是原生计费token，也不是纯CLI正文。既定的存储JSON表示估算仍完整保留，见下表与[口径澄清](analysis-clarifications.md)。

² 每份答案先取两位评分者均分，再按预注册汇报组内中位数，并补充算术平均。量表为事实／覆盖90分＋证据10分，不能把得分当作“事实正确率”。其余主表列为中位数。每题每组只有三次，这是描述性复测，不作显著性或全领域承诺。

## 实现改动

1. **紧凑输出与完整stdout预算。** [CLI](../../../scripts/mc-knowledge.py)新增`--format compact`、`--max-output-chars`与`--provenance`；[输出模块](../../../scripts/mc_knowledge_output.py)返回单行JSON元数据头、换行、原样正文和最终换行。compact默认整个stdout不超过6000个Unicode字符；`--max-chars`继续只限制正文。元数据、告警、范围与续读信息参与预算，不能截坏JSON或静默丢弃告警。预算不足以容纳必要信息时返回非零与`OUTPUT_BUDGET_TOO_SMALL`；极小预算的完整诊断走stderr。帮助／argparse诊断不属于这个stdout数据预算。
2. **可复核来源保留。** compact显示短JAR标识和内容寻址证据入口；完整哈希、原响应及反编译来源保存在不可变证据文件中，按需读取或`--provenance full`展开。没有“首次完整、以后自动隐藏”的会话状态。没有格式环境覆盖且不指定新选项时，默认JSON保留原字段和语义，也不新增证据写入；版本/JAR校验、构造器默认字节码、警告、截断和Wiki修订固定等保护继续保留。
3. **按未解决问题扩读并检查交付覆盖。** [skill](../../../.agents/skills/minecraft-knowledge/SKILL.md)指导已知成员直接查询，参数不清才查帮助、环境确认或故障时用doctor；按问题需要追父类、调用方、数据或整类。收尾对照问题检查结论、关键条件和不确定性。没有加入骷髅、狼或本轮量表的专用必查清单。
4. **错误返回给出下一步。** 重载和类歧义提供结构化argv及安全shell命令；继承成员只给实际声明线索，不自动替换方法或选重载，搜索上限为8层／32类。关闭argparse参数缩写。相关测试位于[输出与恢复测试](../../../scripts/test_mc_knowledge_output.py)。

正式实验结束后，仅澄清了一行`--provenance`帮助文字，修正“默认JSON也始终写证据文件”的误导；查询、渲染、错误处理及skill行为均未再改变。交付版50项单测再次通过。实验原字节保存在`final/`；交付版在`delivery/`，差异见[单行补丁](delivery-help-clarification.patch)与[交付清单](delivery-manifest.json)。交付帮助文案没有重新做模型实验。

## 对照设计与验证范围

| 组别 | CLI | 输出预设 | skill |
|---|---|---|---|
| J | 相同的新CLI | JSON | 旧版 |
| O | 相同的新CLI | compact | 旧版 |
| S | 相同的新CLI | compact | 新版 |

O−J估计输出层配置的整体影响，S−O估计compact基础上的skill调整影响。错误恢复功能在三组共有，**没有单独估计其收益；J也不是未经修改的原CLI**。原CLI只用于兼容性回归。不能把此表直接当作整个新版相对原始方案的全部因果收益，也不能与上一轮不同环境实验的绝对token直接拼接。

- T1复用原骷髅／狼题及原量表；T2为铁傀儡随机伤害与玩家难度缩放；T3为普通／试炼刷怪笼的激活、延迟和失败行为。两道新题的独立参考在正式运行前冻结；T1标准原字节沿用，未适配新答案。
- 随机种子20261025；九个“题目×重复”波次打乱，每波三组随机顺序启动、并发3。每次1200秒上限。首尝试全部保留，无自动重跑或失败替换。
- 模型和high effort由27份原生配置记录确认，累计用量与CLI汇总全部一致。忽略用户配置，关闭memories/apps/plugins等上下文入口，使用中性工作目录和相同公共提示。每次生成缓存起始为空，原始Gradle、OS及网络缓存共享；这不是清空所有缓存的测试。
- 两名独立评分者只看匿名最终答案和冻结参考，不看分组、成本或对方结果。分批交付已完成case，最终全部27份各评两次。两人总分差的中位数为0、均值{S['grader_gap']['mean']:.2f}、最大4分。原始评分、逐项理由和差异全部保留，不按组别裁剪或补分。
- 正式运行墙钟约{duration/60:.1f}分钟，不含实现、参考准备、评分和报告时间。耗时是在并发条件下观察，含网络、推理和本机工具扰动。
- 本次验证为CLI、静态原版来源与模型调查流程；没有启动Minecraft客户端／服务器或真实浏览器，不代表HardRush运行时行为验证。

原始预注册见[protocol.md](protocol.md)，输入和参考哈希见[preregistration-hashes.json](preregistration-hashes.json)、[frozen-source-manifest.json](frozen-source-manifest.json)、[reference-checks.json](reference-checks.json)。分析阶段增加了更准确的解码文本辅助估算、CLI事件级格式审计和结束态输入校验；预注册原文未修改，变化见[分析澄清](analysis-clarifications.md)及[预算错误归类说明](analysis-clarifications-extra.md)。

## 逐题结果

以下峰值输入都是每次模型请求的原生input_tokens峰值，再取各组三次的中位数；百分比负值表示减少。

{chr(10).join(topic_table)}

单改输出在两题下降5.5%—6.7%，在刷怪笼题增加0.6%；不能声称紧凑格式会让每次自适应调查都更省上下文。新skill组在两个新题上也观察到下降，因此效果并非只出现在原题，但三个题目仍远不足以验证普适性。

{chr(10).join(quality_table)}

54份评分未标出主要事实错误，主要扣分来自遗漏或未明确写出条件。T1三组均分约83，新增覆盖检查没有明显改善这个短板；T2/T3接近满分，差异也小。合并质量均分93.56／93.94／94.00不能支持“质量显著提高”。

耗时也不能简单归纳为统一加速：逐题S组中位数都小于O组，但混合九份后的中位数S比O高2.6%；两组总体均值分别为{mean('all','S','elapsed_seconds'):.1f}秒和{mean('all','O','elapsed_seconds'):.1f}秒。保留均值、范围与逐次记录，不挑选有利汇总方式。

### 取得证据后仍漏写的实例

S组`run-01`的`item_30`确实读取了两个护主／协助目标的`canUse`，返回中包含`isOrderedToSit`、`getLastHurtByMob`和`getLastHurtMob`；最终答案仍未展开两个触发及坐下时的对应限制，两位评分者对此一致扣分。这是收尾覆盖检查尚未解决的实例，并非缺少查询入口。见[可移植答案](runs/run-01/attempt-01/portable-answer.md)、[逐次日志](runs/run-01/attempt-01/transcript.md)、[护主源码](runs/run-01/attempt-01/sources/.local/vanilla-26.3-evidence/OwnerHurtByTargetGoal.canUse.java)、[协助源码](runs/run-01/attempt-01/sources/.local/vanilla-26.3-evidence/OwnerHurtTargetGoal.canUse.java)与[核对记录](coverage-check-example.json)。

## 固定查询的输出层直接收益

以下六项在同一冻结CLI与相同正文限制下重放。两种格式的完整正文SHA均相同，全部一页完成，没有额外续读；因此这张表衡量直接序列化变化，不依赖模型选择调查路线。

{chr(10).join(det)}

合计估算token从{jtotal}降至{ctotal}，减少{(1-ctotal/jtotal)*100:.1f}%；字符从{jchars}降至{cchars}，减少{(1-cchars/jchars)*100:.1f}%。部分短查询的字符数略增，不能说每条响应都更短。此处没有触发6000字符总预算，分页正确性由单元测试及实际实验中的截断／续读日志补充验证。

另测“每条compact响应后，再完整读取其首份证据文件”的明确策略，合计估算变为{proof_total} token，超过JSON的{jtotal}；这不是模型必然采取的策略，也不是只提取某个哈希字段的最小成本。它说明出处复读可抵消节省，证据应按需要读取。原始stdout、逐页正文hash和读取策略均在[deterministic-output](deterministic-output/summary.json)。

## 过程与控制变量核验

全部27次结束态CLI／skill／配置哈希与开始时一致。原生输入审计未发现MEMORY_SUMMARY注入标记；独立过程审查逐条检查了skill返回、文件访问、输出模式及扩读行为，结论和覆盖限制见[过程审查](process-review.md)。这些检查提供未观察到污染的证据，不宣称能够证明绝无外部污染。

CLI事件级已识别业务调用共{eligible+fallbacks}个；单列{fallbacks}次预算不足的JSON诊断回退后，剩余{eligible}个中有{confirmed}个输出格式可确认且全部符合指定格式，另外{unknown}个格式不明。采用率只在{confirmed}个可确认项中计算；另有{candidates}条无法可靠解析的候选记录不进入分母。帮助／doctor另计。复杂命令没有按环境变量直接猜作“已采用”，诊断回退保留真实JSON wire格式，也不误判为参与者主动覆盖。详见各次`cli-event-format-audit.json`及原外层`format-audit.json`。

6000字符是compact默认值，**不是实验中的强制上限**；参与者可以提高预算以完成调查。过程报告记录了主动提高预算、整类视图、帮助／doctor、公告和证据再读取等行为与反例。本轮没有重新量化主观“无关信息比例”，不能把工具文本减少直接称为无关信息减少。

| 过程直接计数（各9次） | J | O | S |
|---|---:|---:|---:|
| help查询 | 14 | 17 | 18 |
| doctor查询 | 9 | 11 | 13 |
| 不指定member的源码初始页请求 | 58 | 51 | 41 |
| 官方发布页直接打开 | 7 | 7 | 5 |

这里的“不指定member”只表示请求类视图的起始页，不等同于完整读完一类，也不自动判为无关。新skill没有减少help／doctor；T3中S组这类初始请求18次，反而多于O组16次。因此不能把上下文下降简单解释为每次都更聚焦。O组80次显式设置全响应预算，其中79次高于6000；S组71次设置且均高于6000。过程轨迹包含与期望相反的行为，报告没有把它们排除。

### 其他用量口径

{chr(10).join(storage)}

累计输入通常包含同一历史上下文在多次请求中的重复计入；缓存输入是其中一部分，不能与峰值上下文相加，也不能直接换算为本报告未查询的美元成本。既定工具估算对非字符串原生输出先序列化为JSON，包含存储转义；解码文本段是辅助口径，两者都不等于原生计费token。

## 全部首次结果

{chr(10).join(detail)}

每个运行目录含原始prompt、调用参数、状态、可观察事件／完整工具返回、原生数值用量、最终答案、可移植答案与文本来源。评分原件在`grading-1/`和`grading-2/`，映射和逐项差异在[reviews-unblinded.json](reviews-unblinded.json)及[review-comparison.json](review-comparison.json)。表格数据另有[graded-results.csv](graded-results.csv)和[analysis-summary.json](analysis-summary.json)。

## 交付与复核

- [实现说明](implementation-notes.md)、[实现审查](implementation-review.md)、[变更补丁](implementation.patch)。默认JSON真实查询兼容12/12；CLI单测50/50；harness合成测试16/16；skill格式校验通过。检查原始结果在`compatibility/`、`implementation-checks.json`、`delivery-checks.json`和`auxiliary-checks.json`。
- 冻结参考在`reference/`，旧标准完整来源另存`reference/T1-original/`。T2/T3的132项SHA和T1两个原文SHA均一致。
- 27份文本来源包包含2225个文件，287个原答案本地链接均已转换、无未解析workspace链接；原始答案与日志保持原字节。每次同时提供`source-path-map.json`、`source-manifest.json`、`sources.tar.gz`和可直接访问的`sources/`。原版JAR、模型私有推理、系统指令正文、凭据及原始账户会话不在证据包中。
- 运行`aggregate_results.py`可从本包评分与指标重新汇总；`harness/`保存准备、调度、分析、盲评和导出代码。重新进行模型实验须使用新的实验目录及匹配的JAR/JDK/Vineflower，不能覆盖本次首次结果。固定重放可指定新的`--output`目录。

当前证据支持保留紧凑输出与这版轻量skill，收益定位为减少上下文。帮助／环境探路及答案覆盖仍有改进空间；本轮不据此增加题目专用检查清单，也不声称已经获得普遍质量提升。
'''
(B/'report.md').write_text(report)
print(B/'report.md')
