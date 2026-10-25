#!/usr/bin/env python3
import datetime, json
from pathlib import Path
BASE=Path(__file__).resolve().parent
DEST=Path('/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025')
s=json.loads((BASE/'summary.json').read_text())
assert s['completed_scored_trials']==9
groups=s['groups'];rows=sorted(s['trials'],key=lambda r:(r['group'],r['repeat']))
def num(x):return f'{x:,.0f}'
def decrease(k,g):return (1-groups['A']['median'][k]/groups[g]['median'][k])*100
def link(path,label):return f'[{label}]({path})'
group_rows=[]
for g,label in [('A','Skill＋CLI'),('B','查证路线提示'),('C','自行规划')]:
    d=groups[g]['median'];rg=groups[g]['range']
    group_rows.append(f"| {g} {label} | {d['quality']:.0f}（{rg['quality'][0]}–{rg['quality'][1]}） | {num(d['peak_input_tokens'])} | {num(d['tool_output_tokens_o200k'])} | {num(d['input_tokens'])} | {d['elapsed_seconds']:.1f} |")
trial_rows=[];index_rows=[]
for r in rows:
    trial_rows.append(f"| {r['group']}{r['repeat']} | {r['quality']} | {num(r['peak_input_tokens'])} | {num(r['tool_output_tokens_o200k'])} | {num(r['usage']['input_tokens'])} | {r['elapsed_seconds']:.1f} | {r['noise_percent']['I']:.1f}% |")
    folder=f"runs/{r['id']}"
    index_rows.append(f"| {r['group']}{r['repeat']} | {r['id']} / {r['case']} | [答案]({folder}/answer-portable.md) | [工具日志]({folder}/transcript.md) | [配置与用量]({folder}/metrics.json) |")
noise_rows=[]
for g in 'ABC':
    d=groups[g]['median']
    noise_rows.append(f"| {g} | {d['noise_I_percent']:.1f}% | {num(d['irrelevant_tokens_o200k_estimate'])} | {d['noise_O_percent']:.1f}% |")
tot=s['native_usage_total']
report=f'''# Minecraft 原版知识查询方案对照实验

2026 年 10 月 25 日，Asia/Singapore。研究对象是 Java 26.3 骷髅在细雪中转化为流浪者，以及野生／驯服狼是否主动攻击流浪者。三组各独立运行 3 次，调查、评分和日志分析均使用 **GPT-6.1 Sol / high**。

**本题上，Skill＋CLI 有正向的上下文效率收益；相对只有一句查证提示的方案，收益较小，没有观察到答案质量优势。** 相对完全自由调查，节省更明显。这个结论支持保留轻量方案，不支持把单条查询的高压缩率推广为完整任务的同等收益。

## 主要结果

以下为每组 3 次的中位数；分数括号内为范围。峰值输入是原生用量记录，工具返回量是统一 `o200k_base` 估算。

| 组别 | 完整度与证据／100 | 峰值输入 token | 工具返回 token 估计 | 累计输入 token | 耗时秒 |
| --- | ---: | ---: | ---: | ---: | ---: |
{chr(10).join(group_rows)}

第一组相较第二组：峰值上下文减少 **{decrease('peak_input_tokens','B'):.1f}%**，工具返回量减少 **{decrease('tool_output_tokens_o200k','B'):.1f}%**，累计输入减少 **{decrease('input_tokens','B'):.1f}%**；耗时中位数只减少 **{decrease('elapsed_seconds','B'):.1f}%**。第二组完整度中位数高 2 分。因此，不能把第一组称为各项指标的全面赢家。

第一组相较第三组：峰值上下文减少 **{decrease('peak_input_tokens','C'):.1f}%**，工具返回量减少 **{decrease('tool_output_tokens_o200k','C'):.1f}%**，累计输入减少 **{decrease('input_tokens','C'):.1f}%**，耗时减少 **{decrease('elapsed_seconds','C'):.1f}%**。

第一组耗时范围为 **250.4–255.3 秒**，第二组 **241.3–349.6 秒**，第三组 **237.5–377.4 秒**。本样本中，第一组更稳定，但没有每次都最快。三个对应轮次中，第一组峰值上下文均小于另外两组；峰值输入仅 A／B 的范围有交叠，C 的三次均更高，三组耗时范围则互有交叠。

## 每次结果

| 运行 | 完整度与证据／100 | 峰值输入 token | 工具返回 token 估计 | 累计输入 token | 耗时秒 | 无关正文抽样占比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
{chr(10).join(trial_rows)}

九次均完成，没有超时、模型切换或上下文压缩。三组都核对到核心结论：约 7 秒前置加约 15 秒转化；26.3 中离开细雪也会取消已经开始的转化；野生狼和正常可行动的驯服狼都会主动选择流浪者为攻击目标。低于满分主要反映边界条件遗漏，**不代表相同比例的事实错误**。

评分量表将事实完整度计 90 分，可复核证据计 10 分；各组证据均得满分。常见遗漏是主人被攻击与主人攻击目标这两种协助触发、命令坐下对护主目标的限制，以及转化时普通 AI／冻结资格的区分。两位独立评分者使用随机样本编号，不看到组别或成本。12 个评分项 × 9 份答案中有 2 项初始分歧，均在不揭示组别时复核一致，原始评分和裁决建议均保留。[量表](reference/rubric.json)、[复核说明](reference/audit.md)、[裁决结果](adjudication.json)。

## 上下文中的无关内容

每次从模型实际收到的工具返回文本中，按 token 位置等分成 32 个区间，各随机抽取约 128 token 的目标窗口，前后各提供 64 token 辅助判读。共 **288 个窗口**，由未被提供组别和答案的 GPT-6.1 Sol / high 独立标注：E 为相关机制证据，O 为必要的检索、工具说明和出处开销，I 为无关正文，F 为失败／错误／空结果噪声。

下表均为每组各次估计值的中位数。无关正文估计 token 先按每次“工具返回量 × 抽样 I 比例”计算，再取中位数；不能直接将表内两项中位数相乘。

| 组别 | I 无关正文比例 | I 无关正文 token 估计 | O 必要操作开销比例 |
| --- | ---: | ---: | ---: |
{chr(10).join(noise_rows)}

第一组的无关正文估计量中位数比第二组少 **{decrease('irrelevant_tokens_o200k_estimate','B'):.1f}%**，比第三组少 **{decrease('irrelevant_tokens_o200k_estimate','C'):.1f}%**。但这是有限窗口的用途判断，不能视为全日志精确比例或统计显著性。A3 的无关比例为 48.4%，高于 B3 的 45.3%，所以也不能说第一组始终更干净。O 中的版本验证、出处、参数帮助并非都能删除。

抽样中常见的无关内容包括完整更新公告中的地图、结构、界面、渲染修复，其他实体的转化规则，以及狼的外观、繁殖等成员。这个问题三组都有；读取一个完整官方发布页，并不会因为来源可靠而变得与本题相关。[抽样与逐项标签](noise-audit/notes.md)。

## 日志揭示的收益和局限

**CLI 降低了环境定位与自行造查询工具的成本。** 第一组三次使用项目 CLI，分别在命令文本中调用 37、29、29 次。第二组利用提示定位 Gradle 缓存，手工提取并反编译相关类。第三组三次都自行取得官方版本包；C1、C2 下载服务端包，C3 下载客户端包，然后围绕可用工具做字节码查询。C1 的自写解析脚本出现 `op_type` 错误后修复；C3 尝试下载 JDK 遇到 HTTP 403，后改用 Python 字节码库。这些都是完整任务中实实在在的探索成本。

**只有查证提示，已经能拿到较大一部分收益。** 第二组同样做到了版本匹配和直接代码查证，最终完整度略高；其峰值中位数距离第一组只差约 5,580 token。当前实验无法拆开“skill 的路线建议”和“CLI 的实现”各自贡献，比较的是整套方案。

**小片段查询没有自动变成小任务上下文。** 第一组仍读取整类、完整发布页和多次相关类片段，重复接收查询元数据。Skill 本体约 2,356 字符、1,141 个 `o200k_base` token，已计入调用成本，本身不是最大开销。各组都有整页或整类带来的无关内容，因此先前按单个方法对比整类得到的大幅缩减，不能作为本次端到端收益。

**CLI 还有可用性摩擦。** A1 在错误的声明类查询 `reassessTameGoals`；A2、A3 查询重载 `Mob.convertTo` 时需补 descriptor；A2 还尝试了不存在的 `--java`，被 argparse 解释成未提供值的 `--java-home`。这些失败没有妨碍最后完成，但说明参数发现和输出反馈仍可改善。

第一组并没有最少的 shell 命令：分别 42／32／33 次，第二组 21／21／20 次，第三组 25／11／15 次。一次命令可以批量返回大量内容，模型也可以并行调用多个命令，因此不能把“工具调用少”等同于“上下文少”。原生模型响应次数分别为 A 15／12／13、B 14／20／13、C 13／14／17。

**读到证据也不等于最终交付完整。** 第一组三次都读取了 `OwnerHurt*Goal`，最后仍没有解释主人受击与主人主动攻击的两个协助触发，以及命令坐下对它们的限制。九份答案都花了篇幅讨论精确 tick 边界，却共同漏掉这些行为分支；单纯继续增加检索量解决不了这个交付问题。[独立日志分析](comparison-review.md)给出了逐次调用出处。

## 对现有方案的建议

1. **保留 Skill＋CLI 的轻量定位。** 在这项题目中，它以相近完整度换来了较小的上下文和稳定的耗时，收益真实但不巨大。
2. **优先减少重复元数据和泛读。** 可考虑默认精简输出、按需展开完整 provenance；版本已由本地包校验后，避免再读取整页发布公告。后者是工作流改进建议，不能把必要联网验证一概取消。
3. **改善成员定位与重载错误的下一步提示。** 错误可附可直接执行的候选命令；明确构造器 Java 辅助视图的实际用法，减少猜参数。
4. **在交付前核对问题覆盖。** 将已找到的证据逐一对应到最终结论，先回答阶段、中断、野生／驯服及坐下／护主等分支，再补精确 tick 和装备等可选深挖。
5. **用新题复测后再扩大结论。** 加入纯 JSON 数值、父类／目标选择链、版本冲突和跨版本查询等题型；冷／热生成缓存分开，保留同样盲评和每题三次。当前结果不足以证明对所有 Minecraft 开发任务都节省相同比例，也不足以支持新增 MCP、知识库或向量检索。

本轮仅实验和记录，没有修改现有 skill／CLI，也未运行游戏客户端、游戏服务器或真实浏览器验证。

## 实验控制和指标口径

- 三组采用用户给出的相同两道问题，仅路线提示不同；共同增加中文、可复核出处、只调查原版、不读历史报告／其他实验目录、不启动子代理或游戏的要求。完整提示词逐次保存。
- 九个独立 CLI 会话，`gpt-6.1-sol`、`high`；三轮并行，每轮 A／B／C 各一次，轮内启动次序按固定种子打乱。CLI 版本为 0.163.0。
- 使用独立临时工作目录；三组都提供相同 `gradle.properties` 和 JDK 定位文件，只有第一组提供 skill 和两个 CLI 脚本。每次生成缓存为空，共享既有原始 Gradle 缓存；没有清理操作系统页缓存、远端 HTTP 缓存或模型提示缓存。
- 初始上下文审计确认 B、C 没有 Minecraft skill，也没有历史 memory summary。一般个人／系统技能目录仍共同存在；这是隔离项目知识工具的实验，不是绝对空白系统提示。命令文本未发现跨实验目录读取、既有研究报告读取或 B／C 使用 CLI；该检查不等价于文件系统级完全隔离。[隔离审计](isolation-audit.json)。
- 评分基准由独立代理从 26.3 源码／字节码建立，在首轮进行期间、读取实验最终答案前冻结；另一代理复核核心结论。不是在第一条实验调用前完成的预注册。盲评隐藏组别、耗时和 token，但引用格式仍可能提示调查路线。
- **峰值输入**：原生 `last_token_usage.input_tokens` 的最大值，是每次模型调用看到的输入量，包含共同基础指令、工具说明及历史。九次没有上下文压缩。
- **累计输入**：原生 `turn.completed.usage.input_tokens`，同一历史会在多次调用中重复计入，不能称为上下文窗口占用。缓存输入另列，缓存不会让文本从模型上下文中消失。
- **工具返回估计**：从记录的模型可见外层工具返回提取文本，使用同一 `o200k_base` 编码器计数；包括重复返回与元数据，不把命令完整 stdout 误当成外层截断后模型仍看到了全文。它是公开统一估算口径，不声称是 GPT-6.1 的真实 tokenizer。
- **耗时**：CLI 启动至退出的墙钟时间，包括查询、下载、生成、启动收尾和并行资源竞争；不能解释为纯模型思考时间。
- **无关内容**：单独审计的分层窗口抽样估计，不同于错误率。32 个窗口不是 32 次独立实验，实验重复数仍为每组 3。

九次调查累计原生输入 **{num(tot['input_tokens'])} token**，其中缓存输入 **{num(tot['cached_input_tokens'])}**；输出 **{num(tot['output_tokens'])}**，记录的 reasoning 子项为 **{num(tot['reasoning_output_tokens'])}**。这些是九次调查的统计，不包含环境预检、标准答案构建、评分、日志审计和报告撰写。未根据 token 反推账号费用。

## 资料索引

| 运行 | 日志编号／盲评编号 | 最终答案 | 可观测调用轨迹 | 指标 |
| --- | --- | --- | --- | --- |
{chr(10).join(index_rows)}

[完整聚合 JSON](summary.json)、[CSV 表](results-table.csv)、[实验协议](protocol.json)、[标准答案与源码证据](reference/standard_answer-portable.md)、[独立日志比较复核](comparison-review.md)。

每次目录还保存提示词、调用参数、原生事件、逐响应用量、模型可见工具返回和所引用的本地源码。`workspace-evidence.tar.gz` 保留该次生成的文本资料；未复制 Minecraft JAR、JDK 或 Python 依赖。日志保留公开消息与工具轨迹，不包含未公开内部推理或完整私有指令。复算和重跑方法见 [README](README.md)。
'''
DEST.mkdir(parents=True,exist_ok=True)
(DEST/'report.md').write_text(report)
print(DEST/'report.md')
