# 27 次输出格式／skill 消融复测

本目录仅提供可重复 harness。默认没有模型调用；只有 `run_experiment.py --run` 会启动 Codex。`test_harness.py` 全部使用合成资料，不启动模型，也不读 reference。

固定设计：3 topic × 3 arm × 3 repeat；seed=20261025；九波 topic/repeat 组合先打乱，每波 J/O/S 也按 seed 打乱后按该顺序启动，并发数 3；每次限时 1200 秒。J=JSON＋原 skill，O=compact＋原 skill，S=compact＋新 skill。全部使用同一最终 CLI、相同冻结配置和相同题目 prompt。三组都明确允许使用 skill，且共同要求沿用 MC_KNOWLEDGE_FORMAT 预设。显式同格式参数允许；异格式覆盖保留并标 protocol deviation。

MC_KNOWLEDGE_FORMAT 同时设置在 Popen 环境和 Codex `shell_environment_policy.set.MC_KNOWLEDGE_FORMAT` 中，保证实际工具子 shell 可继承。其余公共环境由显式 allowlist 选择，第一次运行封存，恢复运行如环境变化即拒绝混用。代理 CLI 忽略用户配置，project_doc_max_bytes=0，禁 memories/plugins/apps/multi_agent/chronicle/hooks/host skill discovery。仅复制各组指定 skill，没有项目 AGENTS.md。

## 冻结与准备

主持人先准备 `final/scripts/mc-knowledge.py`、全部 `final/scripts/mc_knowledge*.py`（包含输出模块）、`final/.agents/skills/minecraft-knowledge/SKILL.md`。J/O 原 skill 固定取 `baseline/.agents/skills/minecraft-knowledge/SKILL.md`。不要放入参考答案。

```sh
python3 /tmp/hardrush-mc-optimization-20261025/harness/test_harness.py
python3 /tmp/hardrush-mc-optimization-20261025/harness/seal.py
python3 /tmp/hardrush-mc-optimization-20261025/harness/run_experiment.py --prepare
python3 /tmp/hardrush-mc-optimization-20261025/harness/run_experiment.py --dry-run
```

seal 会复制同一份 gradle.properties 与 java-home.txt 到 inputs/ 并封存所有 CLI/skill/config SHA-256。prepare 生成 27 个独立 workspace，各自 `.local/mc-knowledge` 初始为空。已有原始 Gradle 缓存共享，OS/page/network 缓存不重置。seal/prepare 均拒绝覆盖已有实验。

## 正式执行

```sh
python3 /tmp/hardrush-mc-optimization-20261025/harness/run_experiment.py --run
```

可以 `--only-wave 1` 做明确选择的首波，随后无参数 `--run` 会跳过已有状态，仅启动未启动样本。短预检应放在不同目录，不写本正式 manifest 的 status。每波按 manifest 中的顺序启动三组，先前子进程建立后才允许后者建立，每组三组仍并发执行。

首次结果是主分析，失败、超时、部分答案均保留，不自动补样本。再执行 `--run` 不覆盖任何已有状态。只有显式 `--run --retry-failed` 创建新的 attempt，成功结果永不覆盖；额外 attempt 不替代 primary N=27。崩溃遗留的 run.lock 不会自动删除，主持人应检查 pid 后手动移除。

保存完整实验 prompt、参数、状态、过滤推理后的完整 CLI 事件、答案。stderr 只作本地诊断，不公开导出。原始 native rollout 不复制；analyze 仅提取可观测消息/工具调用/外层工具返回、model/effort、数值 token_usage_record。私有指令只存 SHA-256/长度/污染标记计数，内部推理正文及加密 reasoning 均不导出。

## 分析、盲评、导出

```sh
python3 /tmp/hardrush-mc-optimization-20261025/harness/analyze.py
python3 /tmp/hardrush-mc-optimization-20261025/harness/prepare_blind.py
python3 /tmp/hardrush-mc-optimization-20261025/harness/summarize.py
python3 /tmp/hardrush-mc-optimization-20261025/harness/export_bundle.py
```

analyze 默认从 `~/.codex/sessions` 按精确 thread id 找到 native rollout；可用 `--sessions-root` 替换根目录。分别记录每次 native usage、累计 native usage、CLI 累计 usage、peak input，以及实际 native model/effort，不能用模型宣称替代 native 记录。外层工具返回 tiktoken=o200k_base，复用 `/tmp/hardrush-mc-experiment-20261025/analysis-deps`。既定tool_output_tokens_o200k保留原值口径：native string直接计数，非string则对存储JSON序列化表示（含多层转义/包装）计数，不能称为模型可见净正文或原生计费。另提供decoded native text-part字符/token估算，仍含工具包装，不能替代既定指标，也不等同CLI正文或计费。

格式审计没有 wrapper、telemetry 或额外参与者提示。检查可见调用参数、固定环境与 compact format 元数据标记；JSON 仅在完整独立结果可解析时确认。复杂 shell、管道、重定向、动态调用或不能辨认的输出列 unknown，不按 env 推断“已验证”。显式 `--format`、环境覆盖、与组别不符的 protocol deviation 单列；报告确认分母和 unknown 数。

原native外层审计format-audit.json及format_adoption字段保持独立。新增cli-event-format-audit.json及cli_event_format_adoption，使用CLI item.completed/command_execution中的每个真实command与aggregated_output；只静态解包常见shell的简单-lc/-c包装，复用单命令审计。批量函数调用因此可逐个核验；多指令/重定向仍unknown。doctor和help单列诊断调用，不计入业务格式采用分母。两类审计不能混称同一个分母。

对已结束的completed/failed/timeout等attempt，analyze还会逐项比对initial-files.json所记录输入的当前SHA，输出initial-input-integrity.json和metrics摘要。缺失、改写或替换成symlink均记录协议偏离，不修复、不排除。未开始/运行中仅标pending，不读取当前输入作结束态判定。export的workspace-inputs明确是导出时结束态，workspace-inputs-state.json同时给出起始hash与final/baseline/inputs还原路径，不把当前文件误称初始快照。

prepare_blind 用独立固定 seed=618032 打乱 case id，生成 reviewer-1、reviewer-2 两份同样答案、不同评审顺序的独立包。只使用 primary attempt，失败 case 仍保留。把各自包单独给评分者，冻结 rubric 由主持人另行提供，不给 mapping/分组/成本/另一评分者结果。此脚本不读取 reference。结果写 grading-1/case-NN.json 与 grading-2/case-NN.json 后重跑 summarize，才生成主持人专用解盲合并结果。按运行前protocol，本次只做答案双人盲评，不做量化noise评分。

export 默认输出到实验目录内新的 export-bundle，拒绝覆盖且拒绝写到实验目录之外。包含可观测日志、指标、评分、初始输入、harness 与快照，不包含私有 instruction/reasoning、原始会话或 stderr。生成缓存不自动公开导出，答案本地引用的可移植性因此受限；公开分享前应人工审查工具输出与生成证据。

## 确定性同查询对比（不调用模型）

```sh
python3 /tmp/hardrush-mc-optimization-20261025/harness/deterministic_compare.py --plan
# 最终CLI冻结并seal后，由主持人显式运行：
python3 /tmp/hardrush-mc-optimization-20261025/harness/deterministic_compare.py --run
```

固定使用原12项兼容查询中的6项有效源码/数据查询（索引3—8），不读取参与者结果或参考答案。两种格式使用同一个最终CLI和同一个独立workspace/cache，每个查询先JSON预热一次，再按固定交替顺序测量json/compact，均通过MC_KNOWLEDGE_FORMAT而非显式format参数选择。预热不计入输出比较，原始参数与输出均另存。纯确定性指固定查询与读取策略；实际elapsed_ms字段仍可能变化，不做耗时因果归因。

分别报告首响应字符/o200k token、输出截断次数、按next续读至结束的CLI调用/续页次数与总输出量；只有双方完整正文hash一致，才可称为读取同一完整正文的成本比较。默认JSON无全响应上限、compact有6000字符全响应上限，首响应正文范围可能不同，必须一起呈现，不能只比较短响应。

另外执行独立明确的“拿到首响应来源JAR完整SHA256”策略：JSON已带完整hash则零次额外证据读取；compact若仅带短身份，读取一次其内容寻址证据文件，校验文件hash，并统计整份文件的字符/token。该策略单列，不假装是模型实际选择，也不将首响应减少直接等同于累计token收益。脚本仅测独立CLI stdout及显式证据文件文本，不包含真实模型工具外层包装或原生计费。

默认结果写deterministic-output/，拒绝覆盖，并在后续export_bundle中自动包含其测量文件（不包含workspace缓存）。这组固定查询结果可支持输出层机制说明，不能推出模型准确率、检索策略或累计输入的因果收益。

## 可移植来源打包（全部结束后）

```sh
python3 /tmp/hardrush-mc-optimization-20261025/harness/package_sources.py
# 先完成export_bundle.py，确认全部assigned attempt均终态后：
python3 /tmp/hardrush-mc-optimization-20261025/harness/package_sources.py --run
```

默认只显示计划，不读当前答案或缓存正文。显式run在所有attempt为终态后，才扫描workspace的UTF-8纯文本（java/json/txt/html/md/py等）和原始可观测文件。排除JAR/class/zip等二进制、凭据路径、超限文件、指向workspace之外的symlink；不扫描home，不读凭据文件。私密模式命中仅在source-privacy-scan.json报告路径/类型/行列，不打印值，并在生成来源archive/portable answer前中止。模式扫描不能证明绝无私密内容。

每attempt生成sources.tar.gz，archive中保留workspace相对路径；source-manifest.json区分cli_cache/other_workspace_text及initial_input，附逐文件SHA与跳过原因。另写sources/文本副本供可移植链接实际打开，source-path-map.json记录原/tmp和/private/tmp别名到archive/副本的映射。answer-original.md保持原字节；portable-answer.md仅重写能解析、存在且已纳入archive的workspace链接，不改代码块内示例或外部链接，并统计未解析/未纳入链接。原始answer与日志从不改写，original-file-hashes.json及结束复核保留未变证据。

默认在既有lab/export-bundle中新增这些导出物，不能写formal/reference/final等目录，不能覆盖既有来源包。当前版本不压缩或读取workspace外部引用；引用JAR或不支持的Markdown链接将保留原文并记录局限。全部操作只在主持人显式运行后发生。
