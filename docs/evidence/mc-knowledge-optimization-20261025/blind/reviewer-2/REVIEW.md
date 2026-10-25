请独立评审本目录中的匿名答案。请勿查看其他评分者的结果、blind-map.json、实验分组、工作目录、成本指标或运行顺序。

先仅使用 case 文件中的 question 与 answer，配合主持人另行提供的相应题目冻结 rubric 评估事实正确性、完整性、出处可复核性与版本限定。请按 rubric 的原始维度和分值评分，逐项写出证据，不因长短或表达自信加分。

如 execution_state 不是 completed 或 answer_present=false，保留该 case 并写明“执行失败/部分答案/不可评”，不得补造答案或跳过样本。不得向实验参与者或其工作目录提供 rubric 或参考答案。

将每个 case 的 JSON 结果写到主持人指定的 grading-N/case-NN.json，结构示例：
{"case_id":"case-01","topic":"T1","assessable":true,"rubric_scores":{},"findings":[],"citations":[]}
不要在评分文件中猜测分组。每位评分者独立完成后，再由主持人比对分歧。
