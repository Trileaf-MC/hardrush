# 委派规则（delegate-skills）

本文件只约束担任编排者的 Claude 主会话。被委派的实现者（Codex、Cursor 等）不读这个文件，它们读的是 AGENTS.md 和你写的任务书。

lane 定义在 `~/.config/delegate-skills/config.json`：`execute`、`research`、`critic-codex`（均为 codex-delegate），`critic-cursor-grok`（cursor-delegate）。

## 分工

你自己做，不委派：

- 产品设计、架构判断、AGENTS.md 里 Level 2 的重构方案。
- mixin 和适配层的结构性决策。
- 任务拆分、写任务书、验收、提交。
- 很快就能内联做完的小改动；验收标准说不清的任务；需要真实客户端或游玩才能判断的工作。

委派给 codex-delegate，不要自己做，也不要派 Claude 子代理：

- `--lane execute`：范围明确、有客观验收标准的实现。最合适的是 `core` 和 `persistence` 里有单元测试覆盖的改动、批量机械改动、补测试。
- `--lane research`：大范围只读调研，例如定位代码、梳理调用链、盘点某类用法。
- 原版机制查证：minecraft-knowledge 要往 `.local/` 写缓存，在只读沙箱里会失败。用 `--lane research --sandbox workspace-write` 派发，任务书写明不得改动受版本控制的文件，回来后确认 `touchedFiles` 为空。

## 派发参数

- `--cd` 指向你当前所在的工作区；在 worktree 里就用 worktree 路径。并行的写任务各用独立 worktree，同一工作区内串行。
- 凡是要跑 Gradle 的任务都加 `--network-access`：Gradle 需要本地 socket，否则在沙箱里无法启动。续聊时要重新传，并同时带上 `--sandbox workspace-write`。
- 实现类任务加 `--timeout`，例如 `--timeout 2h`。

## 任务书必须写明

- 角色：你是实现者，范围以任务书为准。AGENTS.md 中“你的角色”和 Level 1、Level 2 重构不适用于本次任务；范围之外发现的问题只写进报告，不要动手。
- 不提交。不使用 `hardrush_server`、`hardrush_client` MCP 工具，不启动测试服或客户端。
- 验收命令：按改动涉及的模块列出，例如 `./gradlew :core:test --offline --console=plain`、`:persistence:test`、`:platforms:mc263:test`。gametest 由你自己在验收时跑，不交给实现者。
- 涉及原版机制、数值或行为时：要求按 `.agents/skills/minecraft-knowledge/SKILL.md` 查证，并在报告里给出证据来源。
- 报告格式：改了什么、验收命令的实际输出、做出的设计取舍、范围之外的发现。

## 验收

- 不采信实现者的自述：自己重跑验收命令，对照任务书读 diff，检查有没有超出范围的改动。
- 实现者报告的设计取舍和范围外发现，按 AGENTS.md“决策准则”的分级处理并告诉用户，不要默默吸收或丢弃。
- 需要返工时，用 `--session <threadId>` 发增量任务书。

## 方案的对抗性评审

当你制定方案/计划的时候，如果用户明确提及”方案要对抗性评审“，那么你的方案/计划在给用户看之前先评审：

- 并行派发 `codex-delegate --lane critic-codex` 和 `cursor-delegate --lane critic-cursor-grok`，两者都是只读。
- 任务书包含方案全文、相关代码位置，以及 AGENTS.md“关于功能”一节作为评审标准，要求对方逐条列出反对意见和理由。
- Cursor 的只读靠 plan 模式，评审后确认 `touchedFiles` 为空。
- 逐条判定采纳或驳回并写明理由，修订方案。向用户呈现方案时附上评审摘要：采纳了什么、驳回了什么、仍有分歧的点。
