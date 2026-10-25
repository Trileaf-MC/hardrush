# 实验材料使用说明

先读 [报告](report.md)。逐次答案、公开消息和完整工具轨迹位于 `runs/run-XX/`。报告里的 A/B/C 表示三种实验条件；`blind-map.json` 保存评分时使用的随机编号映射。

## 文件含义

- `manifest.json`：模型、推理强度、固定提示、运行顺序、skill/CLI 快照哈希。
- `protocol.json`：控制条件、指标口径、盲评和抽样限制。
- `metrics.json`、`summary.json`、`results-table.csv`：逐次及分组统计。
- `runs/*/events.jsonl`：CLI 原生可观测事件；`observable.jsonl`：公开消息、工具调用与模型可见外层返回，以及原生 token_count。
- `runs/*/transcript.md`：便于阅读的完整公开调用轨迹。批量函数返回可能截断，指标以该层模型实际收到的文本为准，不能用更长的单条命令 stdout 替代。
- `runs/*/tool-outputs.json`：逐条模型可见返回，带字符量及统一 o200k_base 估算。
- `runs/*/token-records.json`：每次模型响应的原生用量。`input_tokens` 是累计用量，峰值从各次输入量取最大值。
- `runs/*/answer.md`：原始答案；`answer-portable.md` 将本地引用指向已保留的 `sources/` 文件。外部网址保持原样。
- `runs/*/workspace-evidence.tar.gz`：工作目录中生成的文本资料，不含 Minecraft JAR、JDK、依赖包和私有凭据。
- `reference/`：独立冻结的评分基准、核对证据和交叉审查。`standard_answer.md` 保留原文以供哈希校验，`standard_answer-portable.md` 仅重定位文件链接。
- `grading-1/`、`grading-2/`：两次独立盲评原始结果及裁决建议；`adjudication.json` 保存最终两处裁决。
- `blind/`、`noise-audit/`：匿名答案、分层抽样窗口、288项逐窗口标签与方法说明。

CLI JSON 事件与用量由本机 Codex 0.163.0 生成；其非交互调用形式也可参照 [官方文档](https://learn.chatgpt.com/docs/non-interactive-mode)。模型和 high 配置由各次原生 turn_context 再次核对。日志不导出未公开内部推理、账号标识或完整私有指令；初始上下文只导出项目技能/记忆污染检查与哈希。

## 复核已有数据

在本目录运行：

```sh
python3 verify_saved.py
python3 summarize.py
```

第二条仅从已有指标、两份评分和标签重新生成聚合，不调用模型。两处裁决在脚本中明示。`verify_saved.py` 还会核对9次原生用量、峰值、配置、提示词哈希、18份评分、288个抽样窗口及答案的本地链接。

`analyze.py` 是本次从本机原始会话记录提取指标的脚本，依赖原始会话位置；用于保留计算过程。再次核算可见文本的估计 token 时使用 `tiktoken==0.14.0` 的 `o200k_base`。这不是 GPT-6.1 专属 tokenizer 的声明。

## 重跑实验

将 `run_experiment.py` 复制到一个新的临时目录，在该目录执行：

```sh
python3 run_experiment.py --prepare
python3 run_experiment.py --run
```

脚本使用本机已有 Codex 钥匙串登录，明确指定 `gpt-6.1-sol` 和 `high`；项目路径和原始 Gradle/JDK 依赖位置按本机环境记录在脚本与快照中。准备步骤拒绝覆盖已有运行目录。所有阶段时间上限为每次20分钟，每轮同时运行三种条件，连续三轮。

本次为同一对题目的探索性实验。不存在对所有 Minecraft 查询的统计泛化保证；操作系统缓存、HTTP缓存、提示缓存和服务端调度未被重置。原始日志与逐次结果均保留，未挑选成功样本或删除异常值。
