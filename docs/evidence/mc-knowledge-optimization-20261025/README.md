# Minecraft knowledge优化复测证据

入口：[完整报告](report.md)。

本包保存27次GPT-6.1 Sol high正式调查、54份独立匿名评分、原生数值用量、可观察工具日志、输入快照及可移植来源。全部为首次结果，没有重跑补样。

- [逐次结果CSV](graded-results.csv)与[统计JSON](analysis-summary.json)
- [过程审查](process-review.md)与[独立报告审查](report-review.md)
- [预注册](protocol.md)、[分析口径说明](analysis-clarifications.md)、[格式诊断澄清](analysis-clarifications-extra.md)
- [实现说明](implementation-notes.md)、[变更补丁](implementation.patch)
- [来源打包核验](source-packaging-status.json)、[格式与输入隔离核验](isolation-audit.json)

每次runs/run-NN/attempt-01中提供answer.md原文、portable-answer.md可移植引用版、transcript.md、events.jsonl、native-token-records.json、格式与输入完整性审计；sources/和sources.tar.gz保存文本证据，source-path-map.json映射原始本地路径。原答案和日志未改写。

reference/为事先冻结的参考与量表；grading-1/和grading-2/为独立评分；final/是正式实验代码快照，delivery/是交付版（仅一行帮助文案澄清，详见报告）；baseline/保留优化前文件。

不包含模型私有推理、私有系统指令正文、凭据、原始账户会话或原版JAR。aggregate_results.py可直接重算本包统计；重新调用模型需要在新实验目录准备匹配的运行环境，不能覆盖此处首次结果。
