# 独立匿名答案评分说明

仅使用分配的 blind/reviewer-N 目录和 reference 中对应题目的冻结答案、量表及证据。禁止读取另一评分者结果、blind-map、manifest、运行目录、成本数据或分组；不要猜测组别。分批交给你的 case 只是已完成样本，未交付的 case 暂不评分，后续会补齐全部27个。

逐项评分只依据最终答案，不从工具过程为答案补全事实。按原量表子项给分，不额外要求未问的细节；T1按其冻结的明确子项全分/零分规则。短答案和长答案同一标准。若未完成或没有答案，依量表保留实际可评内容，标记assessable/partial，不能跳过或补造。

每个评分文件写 grading-N/case-NN.json，结构如下：

```json
{
  "case_id": "case-01",
  "topic": "T1",
  "assessable": true,
  "rubric_scores": {"factual_accuracy": 0, "evidence_quality": 0, "total": 0},
  "items": [{"id": "V1", "earned": 0, "maximum": 5, "reason": "引述或准确指出答案中的对应表达，解释所获/失子项分数"}],
  "major_errors": [],
  "omissions": [],
  "citations": [],
  "notes": ""
}
```

T1使用原V1/S1…S6/W1…W4/E1 id，事实满分90、E1证据满分10。

T2按原量表行依次用A1…A6及E1：12、24、14、10、14、16、10。

T3按原量表行依次用P1…P7及E1：18、8、16、8、10、16、14、10。

items逐行earned求和必须等于total，E1等于evidence_quality，其余行合计等于factual_accuracy。保留具体遗漏与错误，不只给一个数字。证据可用目标版本、类/成员、数据路径或相符网页定位，不要求使用参考文件名；答案中的<workspace>只是盲化路径替换，不因此扣分。

本轮不做样本噪声打分，也不评价实现代码。每批完成后只报告已写入的case数量与路径，不向另一评分者或参与者发送消息。主持人在两位独立完成后比对分歧，原始评分始终保留。
